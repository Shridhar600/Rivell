package com.rivell.workbench.knowledge;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.transaction.support.TransactionTemplate;

import com.rivell.workbench.foundation.Texts;
import com.rivell.workbench.organization.AuthorizationService;
import com.rivell.workbench.organization.IdentityProvider;
import com.rivell.workbench.organization.Permissions;

/**
     * Human review of knowledge candidates: approve, edit, reject. Transitions are explicit
 * and pre-publication only; a published candidate is immutable organizational knowledge
 * (Architecture Decision #6.4). Edits preserve the machine-proposed content and record
 * the human result separately (Architecture Decision #5.18 principle).
 *
 * <p>An edit also re-derives the candidate's conditions. Conflict, duplicate, supersession and
 * review-risk categorization are computed from the candidate's <em>effective</em> wording,
 * effective concept, effective positions and applicability scope (AD 6.9/7.15), and those are
 * exactly the inputs a human correction can change. {@link #edit} already refuses a no-op
 * correction, so recomputing here means "a determining input changed" rather than "something was
 * touched", and an edit that leaves a condition intact leaves it genuinely intact.
 */
public class CandidateReviewService {

    private final KnowledgeRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final IdentityProvider identityProvider;
    private final AuthorizationService authorizationService;
    private final DuplicateConflictDetector conflictDetector;
    private final CandidateReviewCategorizer categorizer;

    public CandidateReviewService(
            KnowledgeRepository repository,
            TransactionTemplate transactionTemplate,
            IdentityProvider identityProvider,
            AuthorizationService authorizationService,
            DuplicateConflictDetector conflictDetector,
            CandidateReviewCategorizer categorizer) {
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
        this.identityProvider = identityProvider;
        this.authorizationService = authorizationService;
        this.conflictDetector = conflictDetector;
        this.categorizer = categorizer;
    }

    public KnowledgeCandidate approve(UUID candidateId, String note) {
        return transition(candidateId, "approved", candidate -> {
            if (candidate.reviewStatus() != ReviewStatus.PENDING
                    && candidate.reviewStatus() != ReviewStatus.EDITED) {
                throw new InvalidCandidateReviewStateException(candidate, "approved");
            }
            return new KnowledgeCandidate(
                    candidate.id(), candidate.artifactId(), candidate.organizationId(),
                    candidate.extractionUnitId(), candidate.candidateType(), ReviewStatus.APPROVED,
                    candidate.rawLabel(), candidate.contentText(), candidate.payload(),
                    candidate.legalConceptKey(), candidate.conceptCorrection(),
                    candidate.conceptCorrectedBy(), candidate.conceptCorrectedAt(),
                    candidate.semanticFields(),
                    candidate.editedSemanticFields(), candidate.interpretations(),
                    candidate.editedContentText(),
                    candidate.editedPayload(), candidate.supersedingCandidateId(), candidate.scope(),
                    candidate.conflictSignals(), candidate.reviewCategory(), candidate.reviewReasons(),
                    Texts.normalize(note), Instant.now(),
                    candidate.createdAt(), Instant.now());
        });
    }

    public KnowledgeCandidate edit(
            UUID candidateId, String contentText, CandidatePayload payload,
            ApplicabilityScope scope, SemanticFields semanticFields, String note) {
        return transition(candidateId, "edited", candidate -> {
            if (candidate.isPublished()) {
                throw new InvalidCandidateReviewStateException(candidate, "edited");
            }
            var editedSemantic = semanticFields == null ? SemanticFields.EMPTY : semanticFields;
            var semanticChanged = !editedSemantic.equals(SemanticFields.EMPTY);
            var scopeChanged = scope != null;
            if ((contentText == null || contentText.isBlank()) && payload == null
                    && !semanticChanged && !scopeChanged) {
                throw new IllegalArgumentException("An edit must change content, payload, scope, or semantic fields");
            }
            // A corrected column is a correction to the wording, so render it the way extraction
            // renders machine wording, in the source's column order rather than the arbitrary key
            // order a client or jsonb round trip hands back. Without this the structured
            // correction was stored and then ignored by every consumer of effectiveContentText().
            var correctedPayload = payload == null ? null : candidate.payload().correctedBy(payload);
            var correctedWording = contentText != null && !contentText.isBlank()
                    ? contentText
                    : correctedPayload != null && !correctedPayload.columns().isEmpty()
                            ? correctedPayload.render()
                            : candidate.editedContentText();
            var edited = new KnowledgeCandidate(
                    candidate.id(), candidate.artifactId(), candidate.organizationId(),
                    candidate.extractionUnitId(), candidate.candidateType(), ReviewStatus.EDITED,
                    candidate.rawLabel(), candidate.contentText(), candidate.payload(),
                    candidate.legalConceptKey(), candidate.conceptCorrection(),
                    candidate.conceptCorrectedBy(), candidate.conceptCorrectedAt(),
                    candidate.semanticFields(),
                    !editedSemantic.equals(SemanticFields.EMPTY) ? editedSemantic : candidate.editedSemanticFields(),
                    candidate.interpretations(),
                    correctedWording,
                    correctedPayload != null ? correctedPayload : candidate.editedPayload(),
                    candidate.supersedingCandidateId(), scope != null ? scope : candidate.scope(),
                    candidate.conflictSignals(), candidate.reviewCategory(), candidate.reviewReasons(),
                    Texts.normalize(note), Instant.now(),
                    candidate.createdAt(), Instant.now());
            return recomputeConditions(edited, candidate.artifactId());
        });
    }

    /**
     * Re-derives conflict/duplicate/supersession signals and review-risk categorization for an
     * edited candidate against currently published organizational truth (AD 6.9/7.15). The
     * machine interpretation and the human correction are both untouched; only the derived
     * conditions are refreshed.
     */
    private KnowledgeCandidate recomputeConditions(KnowledgeCandidate candidate, UUID artifactId) {
        var signals = conflictDetector.inspect(candidate,
                repository.findPublishedCandidates(candidate.organizationId()));
        var withSignals = candidate.withConflictSignals(signals);
        var assessment = repository.findArtifact(artifactId, candidate.organizationId())
                .map(KnowledgeArtifact::assessment).orElse(null);
        var categorization = categorizer.categorize(withSignals, assessment);
        return withSignals.withReviewOutcome(categorization.category(), categorization.reasons());
    }

    public KnowledgeCandidate reject(UUID candidateId, String note) {
        return transition(candidateId, "rejected", candidate -> {
            if (candidate.isPublished()) {
                throw new InvalidCandidateReviewStateException(candidate, "rejected");
            }
            return new KnowledgeCandidate(
                    candidate.id(), candidate.artifactId(), candidate.organizationId(),
                    candidate.extractionUnitId(), candidate.candidateType(), ReviewStatus.REJECTED,
                    candidate.rawLabel(), candidate.contentText(), candidate.payload(),
                    candidate.legalConceptKey(), candidate.conceptCorrection(),
                    candidate.conceptCorrectedBy(), candidate.conceptCorrectedAt(),
                    candidate.semanticFields(),
                    candidate.editedSemanticFields(), candidate.interpretations(),
                    candidate.editedContentText(),
                    candidate.editedPayload(), candidate.supersedingCandidateId(), candidate.scope(),
                    candidate.conflictSignals(), candidate.reviewCategory(), candidate.reviewReasons(),
                    Texts.normalize(note), Instant.now(),
                    candidate.createdAt(), Instant.now());
        });
    }

    /**
     * Human LegalConcept correction on a candidate before publication (DW-017):
     * same layering proven in M5 contracts — the machine concept is preserved,
     * a human correction (set key, or blank = clear to unclassified) defines the
     * effective concept that publication and retrieval consume. A correction that
     * changes the effective concept re-runs conflict analysis and review
     * categorization against published truth on the effective concept (AD 6.9/7.15),
     * and an APPROVED candidate demotes to EDITED so publication consumes a
     * re-approved state (AD 6.11: the recorded approval must correspond to what is
     * published). Published and rejected candidates cannot be corrected.
     */
    public KnowledgeCandidate correctConcept(UUID candidateId, String primary) {
        var identity = identityProvider.currentIdentity();
        if (!authorizationService.isAuthorized(identity, Permissions.KNOWLEDGE_REVIEW)) {
            throw new UnauthorizedKnowledgeAccessException(identity.userId());
        }
        if (primary == null) {
            throw new IllegalArgumentException(
                    "A concept correction must set a LegalConcept name or an empty string to clear");
        }
        var normalized = primary.strip();
        if (!normalized.isEmpty()) {
            try {
                com.rivell.workbench.contracts.LegalConcept.valueOf(normalized);
            } catch (IllegalArgumentException e) {
                throw new InvalidConceptCorrectionException(candidateId);
            }
        }
        var candidate = repository.findCandidate(candidateId, identity.organizationId())
                .orElseThrow(() -> new KnowledgeCandidateNotFoundException(candidateId));
        if (candidate.isPublished() || candidate.reviewStatus() == ReviewStatus.REJECTED) {
            throw new InvalidCandidateReviewStateException(candidate, "concept-corrected");
        }
        var corrected = candidate.withConceptCorrection(normalized.isEmpty()
                ? "" : normalized);
        if (!java.util.Objects.equals(corrected.effectiveConceptKey(),
                candidate.effectiveConceptKey())) {
            var signals = conflictDetector.inspect(corrected,
                    repository.findPublishedCandidates(identity.organizationId()));
            corrected = corrected.withConflictSignals(signals);
        }
        var assessment = repository.findArtifact(candidate.artifactId(), identity.organizationId())
                .map(KnowledgeArtifact::assessment).orElse(null);
        var categorization = categorizer.categorize(corrected, assessment);
        corrected = corrected.withReviewOutcome(
                categorization.category(), categorization.reasons());
        var status = candidate.reviewStatus() == ReviewStatus.APPROVED
                ? ReviewStatus.EDITED : candidate.reviewStatus();
        corrected = corrected.withReviewStatus(status);
        boolean persisted = repository.updateConceptCorrection(corrected.id(),
                identity.organizationId(), corrected, candidate.reviewStatus(),
                identity.userId(), Instant.now());
        if (!persisted) {
            throw new InvalidCandidateReviewStateException(corrected, "concept-corrected");
        }
        return corrected;
    }

    private KnowledgeCandidate transition(
            UUID candidateId, String operation,
            java.util.function.UnaryOperator<KnowledgeCandidate> change) {
        var identity = identityProvider.currentIdentity();
        if (!authorizationService.isAuthorized(identity, Permissions.KNOWLEDGE_REVIEW)) {
            throw new UnauthorizedKnowledgeAccessException(identity.userId());
        }
        var candidate = repository.findCandidate(candidateId, identity.organizationId())
                .orElseThrow(() -> new KnowledgeCandidateNotFoundException(candidateId));
        var updated = change.apply(candidate);
        repository.updateReviewState(
                updated.id(), identity.organizationId(), candidate.reviewStatus(),
                updated.reviewStatus(), updated.editedContentText(),
                updated.editedPayload(), updated.editedSemanticFields(), updated.scope(),
                updated.supersedingCandidateId(),
                updated.reviewNote(), updated.reviewedAt(),
                updated.updatedAt(), updated.conflictSignals(),
                updated.reviewCategory(), updated.reviewReasons());
        return updated;
    }

    /**
     * Human bulk approval restricted to server-determined eligible candidates:
     * ROUTINE review category, no conflict/supersession/mismatch/verification signals.
     * Eligibility is never derived from the request; ineligible ids are reported.
     */
    public BulkApprovalResult bulkApprove(java.util.List<UUID> candidateIds) {
        if (candidateIds == null || candidateIds.isEmpty()) {
            throw new IllegalArgumentException("Bulk approval requires at least one candidate");
        }
        var identity = identityProvider.currentIdentity();
        if (!authorizationService.isAuthorized(identity, Permissions.KNOWLEDGE_REVIEW)) {
            throw new UnauthorizedKnowledgeAccessException(identity.userId());
        }
        var approved = new ArrayList<UUID>();
        var skipped = new LinkedHashMap<UUID, String>();
        transactionTemplate.executeWithoutResult(status -> {
            for (var candidateId : new java.util.LinkedHashSet<>(candidateIds)) {
                var candidate = repository.findCandidate(candidateId, identity.organizationId());
                if (candidate.isEmpty()) {
                    skipped.put(candidateId, "not found in this organization");
                    continue;
                }
                var loaded = candidate.get();
                if (loaded.reviewStatus() != ReviewStatus.PENDING) {
                    skipped.put(candidateId, "not pending");
                    continue;
                }
                if (loaded.reviewCategory() != ReviewCategory.ROUTINE) {
                    skipped.put(candidateId, "review-risk category "
                            + (loaded.reviewCategory() == null ? "UNSET" : loaded.reviewCategory()));
                    continue;
                }
                approved.add(approve(candidateId, null).id());
            }
        });
        return new BulkApprovalResult(List.copyOf(approved), java.util.Map.copyOf(skipped));
    }

    public record BulkApprovalResult(java.util.List<UUID> approvedIds, java.util.Map<UUID, String> skipped) {
    }

}
