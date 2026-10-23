package com.rivell.workbench.knowledge;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
        /**
         * The topics this record is about (design 6a): the classification members at or
         * above the topic threshold plus the effective concept, or exactly the human
         * correction once one exists. Review resolution matches clause topics against
         * this set, so a position covering several topics applies to each of them.
         */
        List<String> topicKeys,
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
        Instant updatedAt,
        /**
         * A title a person typed, and nothing else. The machine title in {@link #rawLabel} is kept
         * beside it, as for wording; {@link #effectiveTitle()} is the one to show and publish.
         */
        String editedTitle) {

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
        topicKeys = topicKeys == null ? List.of() : List.copyOf(topicKeys);
        semanticFields = semanticFields == null ? SemanticFields.EMPTY : semanticFields;
        interpretations = interpretations == null ? List.of() : List.copyOf(interpretations);
        conflictSignals = conflictSignals == null ? List.of() : List.copyOf(conflictSignals);
        reviewReasons = reviewReasons == null ? Set.of() : Set.copyOf(reviewReasons);
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /** The title to show: a person's correction when there is one, else the one Rivell read. */
    public String effectiveTitle() {
        return editedTitle == null || editedTitle.isBlank() ? rawLabel : editedTitle;
    }

    public KnowledgeCandidate withConflictSignals(List<ConflictSignal> signals) {
        return new KnowledgeCandidate(id, artifactId, organizationId, extractionUnitId,
                candidateType, reviewStatus, rawLabel, contentText, payload, legalConceptKey, topicKeys, conceptCorrection,
                conceptCorrectedBy, conceptCorrectedAt,
                semanticFields, editedSemanticFields, interpretations,
                editedContentText, editedPayload, supersedingCandidateId, scope, signals,
                reviewCategory, reviewReasons, reviewNote, reviewedAt, createdAt, updatedAt, editedTitle);
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

    public KnowledgeCandidate withConceptCorrection(
            String correction, UUID correctedBy, Instant correctedAt) {
        return new KnowledgeCandidate(id, artifactId, organizationId, extractionUnitId,
                candidateType, reviewStatus, rawLabel, contentText, payload, legalConceptKey,
                correctionTopicKeys(correction),
                correction,
                correctedBy, correctedAt,
                semanticFields, editedSemanticFields, interpretations,
                editedContentText, editedPayload, supersedingCandidateId, scope,
                conflictSignals, reviewCategory, reviewReasons, reviewNote, reviewedAt,
                createdAt, correctedAt, editedTitle);
    }

    /**
     * The topic set of a correction: a key replaces the machine members; a blank
     * correction clears the record to unclassified; no correction keeps the members.
     */
    private List<String> correctionTopicKeys(String correction) {
        if (correction == null) {
            return topicKeys;
        }
        return correction.isBlank() ? List.of() : List.of(correction);
    }

    public KnowledgeCandidate withSemanticOutcome(
            SemanticFields fields, List<FieldInterpretation> fieldInterpretations,
            String conceptKey, List<String> topicKeys) {
        return new KnowledgeCandidate(id, artifactId, organizationId, extractionUnitId,
                candidateType, reviewStatus, rawLabel, contentText, payload, conceptKey, topicKeys,
                conceptCorrection,
                conceptCorrectedBy, conceptCorrectedAt,
                fields, editedSemanticFields, fieldInterpretations,
                editedContentText, editedPayload, supersedingCandidateId, scope,
                conflictSignals, reviewCategory, reviewReasons,
                reviewNote, reviewedAt, createdAt, updatedAt, editedTitle);
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
                candidateType, reviewStatus, rawLabel, contentText, payload, legalConceptKey, topicKeys, conceptCorrection,
                conceptCorrectedBy, conceptCorrectedAt,
                semanticFields, editedSemanticFields, interpretations,
                editedContentText, editedPayload, supersededId, scope,
                conflictSignals, reviewCategory, reviewReasons,
                reviewNote, reviewedAt, createdAt, updatedAt, editedTitle);
    }

    public KnowledgeCandidate withReviewOutcome(ReviewCategory category, Set<ReviewReason> reasons) {
        return new KnowledgeCandidate(id, artifactId, organizationId, extractionUnitId,
                candidateType, reviewStatus, rawLabel, contentText, payload, legalConceptKey, topicKeys, conceptCorrection,
                conceptCorrectedBy, conceptCorrectedAt,
                semanticFields, editedSemanticFields, interpretations,
                editedContentText, editedPayload, supersedingCandidateId, scope,
                conflictSignals, category, reasons,
                reviewNote, reviewedAt, createdAt, updatedAt, editedTitle);
    }

    public KnowledgeCandidate withScope(ApplicabilityScope newScope) {
        return new KnowledgeCandidate(id, artifactId, organizationId, extractionUnitId,
                candidateType, reviewStatus, rawLabel, contentText, payload, legalConceptKey, topicKeys, conceptCorrection,
                conceptCorrectedBy, conceptCorrectedAt,
                semanticFields, editedSemanticFields, interpretations,
                editedContentText, editedPayload, supersedingCandidateId, newScope,
                conflictSignals, reviewCategory, reviewReasons,
                reviewNote, reviewedAt, createdAt, updatedAt, editedTitle);
    }

    public KnowledgeCandidate withReviewStatus(ReviewStatus status) {
        return new KnowledgeCandidate(id, artifactId, organizationId, extractionUnitId,
                candidateType, status, rawLabel, contentText, payload, legalConceptKey, topicKeys, conceptCorrection,
                conceptCorrectedBy, conceptCorrectedAt,
                semanticFields, editedSemanticFields, interpretations,
                editedContentText, editedPayload, supersedingCandidateId, scope,
                conflictSignals, reviewCategory, reviewReasons,
                reviewNote, reviewedAt, createdAt, updatedAt, editedTitle);
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
     * <p>The machine wording is deliberately not re-rendered from its values at read time: the
     * payload is held in {@code jsonb}, which does not preserve object key order, so a re-render
     * could place "Fallback" before "Preferred" and misrepresent the source row. One exception is
     * a clause variant's own wording column: its value does not depend on the order of the other
     * columns, and extraction could not know the label was wording until the field-role decision
     * answered. Reading that column is what keeps a resolved "Provision" or "Text" header from
     * publishing the whole row.
     *
     * <p>Before this rule existed a human spreadsheet-column correction was stored but inert:
     * {@code editedPayload} was persisted while wording, duplicate/conflict analysis and
     * publication all kept reading the original {@code contentText}, so publication silently
     * discarded the correction (AD 6.8, 6.11).
     */
    public String effectiveContentText() {
        return firstReadable(editedContentText, renderedEditedColumns(),
                renderedMachineWording(), contentText);
    }

    /** The wording column of the machine payload, when extraction or the role decision named one. */
    private String renderedMachineWording() {
        if (candidateType != CandidateType.CLAUSE_VARIANT || payload.columns().isEmpty()) {
            return null;
        }
        return payload.wordingText().orElse(null);
    }

    /**
     * The wording of the human's corrected columns. A clause variant's wording is its wording
     * column, as at extraction; any other record reads its values in column order.
     */
    private String renderedEditedColumns() {
        if (editedPayload == null) {
            return null;
        }
        return candidateType == CandidateType.CLAUSE_VARIANT
                ? editedPayload.renderedWording() : editedPayload.render();
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

    /**
     * The payload a reader sees: the human's corrected columns when there are any, else the
     * machine payload. Review classification must read the same values the API shows.
     */
    public CandidatePayload effectivePayload() {
        return editedPayload != null && !editedPayload.columns().isEmpty()
                ? editedPayload : payload;
    }

    /**
     * The role of one payload label: the code synonym table first, else the role the field-role
     * decision resolved for this record's source at compile time.
     */
    Optional<RecordFieldRole> fieldRole(String label) {
        var known = RecordLabelRoles.known(label);
        if (known.isPresent()) {
            return known;
        }
        var resolved = effectivePayload().fieldRoles().get(label);
        if (resolved == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(RecordFieldRole.valueOf(resolved));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Label -> role name of every payload field with a known or resolved role. */
    public Map<String, String> effectiveFieldRoles() {
        var roles = new LinkedHashMap<String, String>();
        for (var key : effectivePayload().orderedKeys()) {
            fieldRole(key).filter(role -> role != RecordFieldRole.OTHER)
                    .ifPresent(role -> roles.put(key, role.name()));
        }
        return roles;
    }

    public KnowledgeCandidate withMachinePayload(CandidatePayload machinePayload) {
        return new KnowledgeCandidate(id, artifactId, organizationId, extractionUnitId,
                candidateType, reviewStatus, rawLabel, contentText, machinePayload, legalConceptKey, topicKeys,
                conceptCorrection, conceptCorrectedBy, conceptCorrectedAt,
                semanticFields, editedSemanticFields, interpretations,
                editedContentText, editedPayload, supersedingCandidateId, scope,
                conflictSignals, reviewCategory, reviewReasons,
                reviewNote, reviewedAt, createdAt, updatedAt, editedTitle);
    }

    /**
     * The role-tagged field values a comparison question needs, in authoritative source
     * order: PREFERRED / FALLBACK (several, ordered) / PROHIBITED / ESCALATION values from
     * the effective payload. A prose candidate with no role-tagged fields returns an empty
     * map; the question then carries its content text as the position.
     */
    public Map<PositionRole, List<String>> effectivePositionFieldValues() {
        var payload = effectivePayload();
        var values = new LinkedHashMap<PositionRole, List<String>>();
        for (var key : payload.orderedKeys()) {
            var value = payload.columns().get(key);
            if (value == null || value.isBlank()) {
                continue;
            }
            var role = fieldRole(key).map(RecordFieldRole::toPositionRole).orElse(null);
            if (role == null) {
                continue;
            }
            values.computeIfAbsent(role, ignored -> new ArrayList<>()).add(value);
        }
        return values;
    }
}
