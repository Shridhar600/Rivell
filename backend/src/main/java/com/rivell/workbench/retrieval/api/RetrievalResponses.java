package com.rivell.workbench.retrieval.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.rivell.workbench.contracts.LegalFact;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Product contract for the M6 evidence developer surface. Items carry typed
 * authority/provenance/applicability; no provider mechanics, raw embeddings,
 * prompts, or chain-of-thought ever appear here.
 */
public final class RetrievalResponses {

    private RetrievalResponses() {
    }

    @Schema(name = "EvidenceBundleResponse")
    public record EvidenceBundleResponse(
            UUID bundleId,
            EvidenceRequestSummary request,
            List<EvidenceItemResponse> items,
            String sufficiency,
            UUID traceId,
            List<String> degradationWarnings) {
    }

    @Schema(name = "EvidenceRequestSummary")
    public record EvidenceRequestSummary(
            String purpose,
            String legalConcept,
            UUID contractId,
            int versionNumber,
            UUID clauseId,
            String effectiveOn,
            Long knowledgePinPublicationNumber) {
    }

    @Schema(name = "EvidenceItemResponse")
    public record EvidenceItemResponse(
            UUID evidenceId,
            String authorityClass,
            String sourceType,
            UUID sourceArtifactId,
            UUID sourceCandidateId,
            UUID sourceContractVersionId,
            UUID sourceClauseId,
            String legalConcept,
            String text,
            PolicyFactsResponse policyFacts,
            List<LegalFact> contractFacts,
            String retrievalReason,
            LaneInfoResponse laneInfo,
            SemanticAssessmentResponse semanticAssessment,
            String policyCompatibility,
            List<String> provenanceNodeIds) {
    }

    @Schema(name = "PolicyFactsResponse")
    public record PolicyFactsResponse(
            java.util.Set<String> positions,
            Boolean requiresApproval,
            String escalationTrigger) {
    }

    @Schema(name = "LaneInfoResponse")
    public record LaneInfoResponse(
            Integer lexicalRank,
            Integer vectorRank,
            Integer fusedRank,
            Double fusedScore) {
    }

    @Schema(name = "SemanticAssessmentResponse")
    public record SemanticAssessmentResponse(
            Map<String, Boolean> judgments,
            String decisionSpecId,
            int decisionSpecVersion) {
    }

    @Schema(name = "RetrievalTraceResponse")
    public record RetrievalTraceResponse(
            UUID id,
            String purpose,
            Map<String, Object> request,
            Map<String, StageSummaryResponse> stages,
            List<UUID> selectedEvidenceIds,
            List<String> degradation,
            Map<String, Long> stageTimings,
            Map<String, String> modelVersions,
            Long knowledgePinPublicationNumber) {

        @Schema(name = "StageSummaryResponse")
        public record StageSummaryResponse(
                String stage,
                int candidateCount,
                List<String> candidateIds) {
        }
    }
}
