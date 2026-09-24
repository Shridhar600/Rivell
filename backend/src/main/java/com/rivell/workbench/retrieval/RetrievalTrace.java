package com.rivell.workbench.retrieval;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Persisted retrieval trace (AD #8.10): stage-level audit/debug/eval data. Never
 * carries whole documents, raw prompts, provider payloads, or chain-of-thought —
 * only ids, counts, ranks, versions, timings, and degradation flags.
 */
public record RetrievalTrace(
        UUID id,
        UUID organizationId,
        UUID contractId,
        int versionNumber,
        UUID clauseId,
        String purpose,
        Map<String, Object> request,
        Map<String, StageSummary> stages,
        List<UUID> selectedEvidenceIds,
        List<RejectedItem> rejectedEvidence,
        List<String> degradation,
        Map<String, Long> stageTimings,
        Map<String, String> modelVersions,
        KnowledgePin knowledgePin,
        Instant createdAt) {

    public RetrievalTrace {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(contractId, "contractId");
        if (versionNumber < 1) {
            throw new IllegalArgumentException("versionNumber must be positive");
        }
        Objects.requireNonNull(clauseId, "clauseId");
        if (purpose == null || purpose.isBlank()) {
            throw new IllegalArgumentException("purpose must not be blank");
        }
        Objects.requireNonNull(request, "request");
        stages = stages == null ? Map.of() : Map.copyOf(stages);
        selectedEvidenceIds = selectedEvidenceIds == null ? List.of() : List.copyOf(selectedEvidenceIds);
        rejectedEvidence = rejectedEvidence == null ? List.of() : List.copyOf(rejectedEvidence);
        degradation = degradation == null ? List.of() : List.copyOf(degradation);
        stageTimings = stageTimings == null ? Map.of() : Map.copyOf(stageTimings);
        modelVersions = modelVersions == null ? Map.of() : Map.copyOf(modelVersions);
        Objects.requireNonNull(knowledgePin, "knowledgePin");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /** Per-stage outcome summary (structured counts + ranks, no raw payloads). */
    public record StageSummary(
            String stage,
            int candidateCount,
            List<String> candidateIds,
            List<Integer> candidateRanks,
            List<String> executionIds) {

        public StageSummary {
            if (stage == null || stage.isBlank()) {
                throw new IllegalArgumentException("stage must not be blank");
            }
            candidateIds = candidateIds == null ? List.of() : List.copyOf(candidateIds);
            candidateRanks = candidateRanks == null ? List.of() : List.copyOf(candidateRanks);
            executionIds = executionIds == null ? List.of() : List.copyOf(executionIds);
        }
    }

    /** Why one candidate was not selected as evidence. */
    public record RejectedItem(
            UUID candidateId,
            String stage,
            String reason) {

        public RejectedItem {
            Objects.requireNonNull(candidateId, "candidateId");
            if (stage == null || stage.isBlank()) {
                throw new IllegalArgumentException("stage must not be blank");
            }
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("reason must not be blank");
            }
        }
    }
}
