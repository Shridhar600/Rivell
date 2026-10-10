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
import com.rivell.workbench.taxonomy.ConceptKey;
import com.rivell.workbench.taxonomy.ConceptRegistry;

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
    private final ConceptRegistry conceptRegistry;

    public CandidateReviewService(
            KnowledgeRepository repository,
            TransactionTemplate transactionTemplate,
            IdentityProvider identityProvider,
            AuthorizationService authorizationService,
            DuplicateConflictDetector conflictDetector,
            CandidateReviewCategorizer categorizer,
            ConceptRegistry conceptRegistry) {
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
        this.identityProvider = identityProvider;
        this.authorizationService = authorizationService;
        this.conflictDetector = conflictDetector;
        this.categorizer = categorizer;
        this.conceptRegistry = conceptRegistry;
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
            var payloadChanged = payload != null && !payload.columns().isEmpty();
            if ((contentText == null || contentText.isBlank()) && !payloadChanged
                    && !semanticChanged && !scopeChanged) {
                throw new IllegalArgumentException("An edit must change content, payload, scope, or semantic fields");
            }
            // A structured correction is a set of corrected values, not wording: it composes onto
            // the values already corrected, so a second correction does not drop the first, and its
            // rendering is derived on read rather than stored.
            var base = candidate.editedPayload() != null
                    && !candidate.editedPayload().columns().isEmpty()
                    ? candidate.editedPayload()
                    : candidate.payload();
            var correctedPayload = payload == null ? null : base.correctedBy(payload);
            if (payloadChanged && correctedPayload.render() == null) {
                // Blanking every cell is not a correction: it carries no wording, so it would be
                // stored, recompute conditions and move the candidate to EDITED while changing
                // nothing a reader can see. Refuse it instead of silently keeping the machine
                // wording behind an edit that looks like it cleared the row.
                throw new IllegalArgumentException(
                        "A structured correction must keep at least one non-blank column value");
            }
            // Correction provenance, and it is a single rule: editedContentText is wording a human
            // typed, and only that. A derived rendering is never written here, because a stored
            // rendering is indistinguishable from human prose and the next structured correction
            // read it back as explicit wording, outranked its own fresh rendering and never took
            // effect. effectiveContentText() resolves wording from explicit text, then the
            // corrected columns, then the machine text, so nothing is derived twice.
            var explicitWording = contentText != null && !contentText.isBlank()
                    ? contentText
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
                    explicitWording,
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
     * Human taxonomy-key correction on a candidate before publication (DW-017):
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
                    "A concept correction must set a taxonomy key or an empty string to clear");
        }
        var normalized = primary.strip();
        if (!normalized.isEmpty()) {
            try {
                var key = ConceptKey.of(normalized);
                if (!conceptRegistry.contains(key)) {
                    throw new InvalidConceptCorrectionException(candidateId);
                }
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
        var applied = repository.updateReviewState(
                updated.id(), identity.organizationId(), candidate.reviewStatus(),
                updated.reviewStatus(), updated.editedContentText(),
                updated.editedPayload(), updated.editedSemanticFields(), updated.scope(),
                updated.supersedingCandidateId(),
                updated.reviewNote(), updated.reviewedAt(),
                updated.updatedAt(), updated.conflictSignals(),
                updated.reviewCategory(), updated.reviewReasons());
        if (!applied) {
            // Somebody else moved the candidate between the read and this write. Reporting the
            // intended state anyway would hand the caller a candidate it does not hold.
            throw InvalidCandidateReviewStateException.lostConcurrentTransition(
                    candidate, operation);
        }
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
                // Bulk approval is the only review path that approves without a human reading the
                // row, so it must not act on a category captured at extraction: another candidate
                // may have been published since, which can turn this one into a duplicate or a
                // contradiction. The stored category stays a snapshot (DW-053 covers the
                // individually reviewed and published paths); the decision is re-derived here and
                // the refusal names the condition that made the candidate ineligible.
                var current = recomputeConditions(loaded, loaded.artifactId());
                if (current.reviewCategory() != ReviewCategory.ROUTINE) {
                    skipped.put(candidateId, "no longer eligible for bulk approval: "
                            + (current.reviewCategory() == null
                                    ? "UNSET" : current.reviewCategory())
                            + (current.reviewReasons().isEmpty()
                                    ? "" : " " + current.reviewReasons()));
                    continue;
                }
                try {
                    approved.add(approve(candidateId, null).id());
                } catch (InvalidCandidateReviewStateException conflict) {
                    // One candidate losing the compare-and-set is that candidate's outcome, not the
                    // batch's: BulkApprovalResult already reports per-candidate skips, so letting
                    // the exception escape would turn a partial success into a failure and leave
                    // the other approvals unapplied. Integrity is unchanged - the candidate that
                    // lost the race is not approved, and it stays in whatever state it now holds.
                    skipped.put(candidateId, "changed state during approval");
                }
            }
        });
        return new BulkApprovalResult(List.copyOf(approved), java.util.Map.copyOf(skipped));
    }

    public record BulkApprovalResult(java.util.List<UUID> approvedIds, java.util.Map<UUID, String> skipped) {
    }

}
