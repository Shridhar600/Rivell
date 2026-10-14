package com.rivell.workbench.review;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.rivell.workbench.taxonomy.ConceptKey;

/**
 * Machine-review observation, not a lawyer-facing issue. One signal per bounded
 * machine determination; humans consume Findings only.
 */
public record RawReviewSignal(
        UUID id,
        UUID organizationId,
        UUID reviewId,
        UUID runId,
        UUID planCheckId,
        SignalType signalType,
        FindingSubjectKind subjectKind,
        UUID subjectHeadingNodeId,
        ConceptKey absenceConcept,
        ConceptKey concept,
        ConceptKey secondaryConcept,
        IssueFamily issueFamily,
        SignalPayload payload,
        List<UUID> evidenceItemIds,
        String decisionSpecId,
        Integer decisionSpecVersion,
        UUID jevExecutionId,
        UUID generativeExecutionId,
        String verificationOutcome,
        String resolvedModel,
        com.rivell.workbench.intelligence.TokenUsage usage,
        Map<String, Object> quality,
        String dedupeKey,
        Instant createdAt) {

    public RawReviewSignal {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(reviewId, "reviewId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(planCheckId, "planCheckId");
        Objects.requireNonNull(signalType, "signalType");
        Objects.requireNonNull(subjectKind, "subjectKind");
        Objects.requireNonNull(concept, "concept");
        Objects.requireNonNull(issueFamily, "issueFamily");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(createdAt, "createdAt");
        evidenceItemIds = evidenceItemIds == null ? List.of() : List.copyOf(evidenceItemIds);
        quality = quality == null ? Map.of() : Map.copyOf(quality);
        if (dedupeKey == null || dedupeKey.isBlank()) {
            throw new IllegalArgumentException("dedupeKey is required");
        }
        if (subjectKind == FindingSubjectKind.CLAUSE && subjectHeadingNodeId == null) {
            throw new IllegalArgumentException("clause signals require a subject clause");
        }
        if (subjectKind == FindingSubjectKind.ABSENCE && absenceConcept == null) {
            throw new IllegalArgumentException("absence signals require the missing concept");
        }
        if (subjectKind == FindingSubjectKind.ABSENCE && subjectHeadingNodeId != null) {
            throw new IllegalArgumentException("absence signals carry no clause subject");
        }
    }
}
