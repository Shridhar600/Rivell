package com.rivell.workbench.knowledge;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A typed, provenance-backed candidate knowledge object produced by the deterministic
 * compiler and refined by bounded semantic intelligence. Candidates never carry
 * organizational authority; only explicit review and publication promote them
 * (Architecture Decision #6.4). Machine semantic proposals and human corrections are
 * both retained: RAW + INTERPRETED + HUMAN CORRECTION, never raw overwritten.
 */
public record KnowledgeCandidate(
        UUID id,
        UUID artifactId,
        UUID organizationId,
        UUID extractionUnitId,
        CandidateType candidateType,
        ReviewStatus reviewStatus,
        String rawLabel,
        String contentText,
        CandidatePayload payload,
        String legalConceptKey,
        String conceptCorrection,
        SemanticFields semanticFields,
        SemanticFields editedSemanticFields,
        List<FieldInterpretation> interpretations,
        String editedContentText,
        CandidatePayload editedPayload,
        UUID supersedingCandidateId,
        ApplicabilityScope scope,
        List<ConflictSignal> conflictSignals,
        ReviewCategory reviewCategory,
        Set<ReviewReason> reviewReasons,
        String reviewNote,
        Instant reviewedAt,
        Instant createdAt,
        Instant updatedAt) {

    public KnowledgeCandidate {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(artifactId, "artifactId");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(extractionUnitId, "extractionUnitId");
        Objects.requireNonNull(candidateType, "candidateType");
        Objects.requireNonNull(reviewStatus, "reviewStatus");
        if (rawLabel == null || rawLabel.isBlank()) {
            throw new IllegalArgumentException("Raw label must not be blank");
        }
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(scope, "scope");
        semanticFields = semanticFields == null ? SemanticFields.EMPTY : semanticFields;
        interpretations = interpretations == null ? List.of() : List.copyOf(interpretations);
        conflictSignals = conflictSignals == null ? List.of() : List.copyOf(conflictSignals);
        reviewReasons = reviewReasons == null ? Set.of() : Set.copyOf(reviewReasons);
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public KnowledgeCandidate withConflictSignals(List<ConflictSignal> signals) {
        return new KnowledgeCandidate(id, artifactId, organizationId, extractionUnitId,
                candidateType, reviewStatus, rawLabel, contentText, payload, legalConceptKey, conceptCorrection,
                semanticFields, editedSemanticFields, interpretations,
                editedContentText, editedPayload, supersedingCandidateId, scope, signals,
                reviewCategory, reviewReasons, reviewNote, reviewedAt, createdAt, updatedAt);
    }

    /**
     * The concept human review consumes: the human correction when one exists
     * (set key, or blank = human-cleared unclassified), else the machine proposal
     * (DW-017 — machine history is never erased).
     */
    public String effectiveConceptKey() {
        if (conceptCorrection == null) {
            return legalConceptKey;
        }
        return conceptCorrection.isBlank() ? null : conceptCorrection;
    }

    public KnowledgeCandidate withConceptCorrection(String correction) {
        return new KnowledgeCandidate(id, artifactId, organizationId, extractionUnitId,
                candidateType, reviewStatus, rawLabel, contentText, payload, legalConceptKey,
                correction,
                semanticFields, editedSemanticFields, interpretations,
                editedContentText, editedPayload, supersedingCandidateId, scope,
                conflictSignals, reviewCategory, reviewReasons, reviewNote, reviewedAt,
                createdAt, Instant.now());
    }

    public KnowledgeCandidate withSemanticOutcome(
            SemanticFields fields, List<FieldInterpretation> fieldInterpretations,
            String conceptKey) {
        return new KnowledgeCandidate(id, artifactId, organizationId, extractionUnitId,
                candidateType, reviewStatus, rawLabel, contentText, payload, conceptKey, conceptCorrection,
                fields, editedSemanticFields, fieldInterpretations,
                editedContentText, editedPayload, supersedingCandidateId, scope,
                conflictSignals, reviewCategory, reviewReasons,
                reviewNote, reviewedAt, createdAt, updatedAt);
    }

    public KnowledgeCandidate withSupersedingCandidateId(
            UUID supersededId, UUID organizationId) {
        if (supersededId == null) {
            throw new IllegalArgumentException("Superseded candidate id must not be null");
        }
        if (supersededId.equals(id)) {
            throw new IllegalArgumentException("A candidate cannot supersede itself");
        }
        return new KnowledgeCandidate(id, artifactId, organizationId, extractionUnitId,
                candidateType, reviewStatus, rawLabel, contentText, payload, legalConceptKey, conceptCorrection,
                semanticFields, editedSemanticFields, interpretations,
                editedContentText, editedPayload, supersededId, scope,
                conflictSignals, reviewCategory, reviewReasons,
                reviewNote, reviewedAt, createdAt, updatedAt);
    }

    public KnowledgeCandidate withReviewOutcome(ReviewCategory category, Set<ReviewReason> reasons) {
        return new KnowledgeCandidate(id, artifactId, organizationId, extractionUnitId,
                candidateType, reviewStatus, rawLabel, contentText, payload, legalConceptKey, conceptCorrection,
                semanticFields, editedSemanticFields, interpretations,
                editedContentText, editedPayload, supersedingCandidateId, scope,
                conflictSignals, category, reasons,
                reviewNote, reviewedAt, createdAt, updatedAt);
    }

    public boolean isPublished() {
        return reviewStatus == ReviewStatus.PUBLISHED;
    }

    public String effectiveContentText() {
        return editedContentText != null ? editedContentText : contentText;
    }

    public Set<PositionRole> effectivePositions() {
        return editedSemanticFields != null && !editedSemanticFields.positions().isEmpty()
                ? editedSemanticFields.positions()
                : semanticFields.positions();
    }

    public PositionRole effectiveVariantRole() {
        return editedSemanticFields != null && editedSemanticFields.variantRole() != null
                ? editedSemanticFields.variantRole()
                : semanticFields.effectiveVariantRole();
    }

    public String effectiveEscalationTrigger() {
        return editedSemanticFields != null && editedSemanticFields.escalationTrigger() != null
                ? editedSemanticFields.escalationTrigger()
                : semanticFields.escalationTrigger();
    }

    public Boolean effectiveRequiresApproval() {
        return editedSemanticFields != null && editedSemanticFields.requiresApproval() != null
                ? editedSemanticFields.requiresApproval()
                : semanticFields.requiresApproval();
    }
}
