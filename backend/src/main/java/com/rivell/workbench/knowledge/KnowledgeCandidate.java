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
        UUID conceptCorrectedBy,
        Instant conceptCorrectedAt,
        SemanticFields semanticFields,
        SemanticFields editedSemanticFields,
        List<FieldInterpretation> interpretations,
        /**
         * Wording a human typed, and nothing else. A structured correction is not wording: it is a
         * set of corrected values in {@code editedPayload}, and its rendering is derived on read by
         * {@link #effectiveContentText()}. Keeping derived wording out of this field is what makes
         * the provenance unambiguous - a stored value here is always explicit human text, so a later
         * correction can never mistake an older machine rendering for a person's decision.
         */
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
                conceptCorrectedBy, conceptCorrectedAt,
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
                conceptCorrectedBy, conceptCorrectedAt,
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
                conceptCorrectedBy, conceptCorrectedAt,
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
                conceptCorrectedBy, conceptCorrectedAt,
                semanticFields, editedSemanticFields, interpretations,
                editedContentText, editedPayload, supersededId, scope,
                conflictSignals, reviewCategory, reviewReasons,
                reviewNote, reviewedAt, createdAt, updatedAt);
    }

    public KnowledgeCandidate withReviewOutcome(ReviewCategory category, Set<ReviewReason> reasons) {
        return new KnowledgeCandidate(id, artifactId, organizationId, extractionUnitId,
                candidateType, reviewStatus, rawLabel, contentText, payload, legalConceptKey, conceptCorrection,
                conceptCorrectedBy, conceptCorrectedAt,
                semanticFields, editedSemanticFields, interpretations,
                editedContentText, editedPayload, supersedingCandidateId, scope,
                conflictSignals, category, reasons,
                reviewNote, reviewedAt, createdAt, updatedAt);
    }

    public KnowledgeCandidate withReviewStatus(ReviewStatus status) {
        return new KnowledgeCandidate(id, artifactId, organizationId, extractionUnitId,
                candidateType, status, rawLabel, contentText, payload, legalConceptKey, conceptCorrection,
                conceptCorrectedBy, conceptCorrectedAt,
                semanticFields, editedSemanticFields, interpretations,
                editedContentText, editedPayload, supersedingCandidateId, scope,
                conflictSignals, reviewCategory, reviewReasons,
                reviewNote, reviewedAt, createdAt, updatedAt);
    }

    public boolean isPublished() {
        return reviewStatus == ReviewStatus.PUBLISHED;
    }

    /**
     * The single authoritative wording of this candidate: what review, conflict analysis,
     * publication and every published read must consume.
     *
     * <p>Resolution order, most specific human act first:
     * <ol>
     *   <li>explicit human wording, the text a person typed;</li>
     *   <li>the wording rendered from the human's corrected structured columns;</li>
     *   <li>the machine/source wording captured at extraction.</li>
     * </ol>
     *
     * <p>The machine wording is deliberately not re-derived from the stored payload at read time:
     * the payload is held in {@code jsonb}, which does not preserve object key order, so a
     * re-render could place "Fallback" before "Preferred" and misrepresent the source row. The
     * machine wording was captured in true source order at extraction and stored as text.
     *
     * <p>Before this rule existed a human spreadsheet-column correction was stored but inert:
     * {@code editedPayload} was persisted while wording, duplicate/conflict analysis and
     * publication all kept reading the original {@code contentText}, so publication silently
     * discarded the correction (AD 6.8, 6.11).
     */
    public String effectiveContentText() {
        return firstReadable(editedContentText,
                editedPayload == null ? null : editedPayload.render(),
                contentText);
    }

    /**
     * The wording invariant, one layer at a time: an explicit human correction wins, then the
     * human's corrected columns, then the machine wording. A layer that is absent or blank is
     * skipped rather than accepted, so a blank correction can never make a candidate with valid
     * machine wording read as blank or null - published knowledge would be invisible, and
     * {@code EvidenceIndexService} hashes this wording before indexing, so it would also silently
     * never become retrievable. A candidate whose source carries no readable wording at all is
     * legitimately null and is not indexed.
     */
    private static String firstReadable(String... layers) {
        for (var layer : layers) {
            if (layer != null && !layer.isBlank()) {
                return layer;
            }
        }
        return null;
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
