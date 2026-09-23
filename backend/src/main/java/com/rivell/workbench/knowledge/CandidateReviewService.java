package com.rivell.workbench.knowledge;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.rivell.workbench.foundation.Texts;
import com.rivell.workbench.organization.AuthorizationService;
import com.rivell.workbench.organization.IdentityProvider;
import com.rivell.workbench.organization.Permissions;

/**
 * Human review of knowledge candidates: approve, edit, reject. Transitions are explicit
 * and pre-publication only; a published candidate is immutable organizational knowledge
 * (Architecture Decision #6.4). Edits preserve the machine-proposed content and record
 * the human result separately (Architecture Decision #5.18 principle).
 */
public class CandidateReviewService {

    private final KnowledgeRepository repository;
    private final IdentityProvider identityProvider;
    private final AuthorizationService authorizationService;

    public CandidateReviewService(
            KnowledgeRepository repository,
            IdentityProvider identityProvider,
            AuthorizationService authorizationService) {
        this.repository = repository;
        this.identityProvider = identityProvider;
        this.authorizationService = authorizationService;
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
                    candidate.legalConceptKey(), candidate.semanticFields(),
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
            return new KnowledgeCandidate(
                    candidate.id(), candidate.artifactId(), candidate.organizationId(),
                    candidate.extractionUnitId(), candidate.candidateType(), ReviewStatus.EDITED,
                    candidate.rawLabel(), candidate.contentText(), candidate.payload(),
                    candidate.legalConceptKey(), candidate.semanticFields(),
                    !editedSemantic.equals(SemanticFields.EMPTY) ? editedSemantic : candidate.editedSemanticFields(),
                    candidate.interpretations(),
                    contentText != null ? contentText : candidate.editedContentText(),
                    payload != null ? payload : candidate.editedPayload(),
                    candidate.supersedingCandidateId(), scope != null ? scope : candidate.scope(),
                    candidate.conflictSignals(), candidate.reviewCategory(), candidate.reviewReasons(),
                    Texts.normalize(note), Instant.now(),
                    candidate.createdAt(), Instant.now());
        });
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
                    candidate.legalConceptKey(), candidate.semanticFields(),
                    candidate.editedSemanticFields(), candidate.interpretations(),
                    candidate.editedContentText(),
                    candidate.editedPayload(), candidate.supersedingCandidateId(), candidate.scope(),
                    candidate.conflictSignals(), candidate.reviewCategory(), candidate.reviewReasons(),
                    Texts.normalize(note), Instant.now(),
                    candidate.createdAt(), Instant.now());
        });
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
                updated.id(), updated.reviewStatus(), updated.editedContentText(),
                updated.editedPayload(), updated.reviewNote(), updated.reviewedAt(),
                updated.updatedAt());
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
        for (var candidateId : new java.util.LinkedHashSet<>(candidateIds)) {
            var candidate = repository.findCandidate(candidateId, identity.organizationId())
                    .orElseThrow(() -> new KnowledgeCandidateNotFoundException(candidateId));
            if (candidate.reviewStatus() != ReviewStatus.PENDING) {
                skipped.put(candidateId, "not pending");
                continue;
            }
            if (candidate.reviewCategory() == null
                    || candidate.reviewCategory() != ReviewCategory.ROUTINE) {
                skipped.put(candidateId, "review-risk category "
                        + (candidate.reviewCategory() == null ? "UNSET" : candidate.reviewCategory()));
                continue;
            }
            approved.add(approve(candidateId, null).id());
        }
        return new BulkApprovalResult(List.copyOf(approved), java.util.Map.copyOf(skipped));
    }

    public record BulkApprovalResult(java.util.List<UUID> approvedIds, java.util.Map<UUID, String> skipped) {
    }

}
