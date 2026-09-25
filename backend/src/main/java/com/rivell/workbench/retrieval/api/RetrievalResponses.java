package com.rivell.workbench.retrieval.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.rivell.workbench.contracts.LegalFact;
import com.rivell.workbench.retrieval.EvidenceItem;

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
            List<String> provenanceNodeIds,
            UUID publicationId,
            Long publicationNumber,
            ApplicabilityScopeResponse applicabilityScope) {
    }

    /** Single source of truth for the product evidence-item shape (M6 + M7 reuse). */
    public static EvidenceItemResponse evidenceItem(EvidenceItem item) {
        return new EvidenceItemResponse(
                item.evidenceId(),
                item.authorityClass().name(),
                item.sourceType(),
                item.sourceArtifactId(),
                item.sourceCandidateId(),
                item.sourceContractVersionId(),
                item.sourceClauseId(),
                item.legalConcept() == null ? null : item.legalConcept().name(),
                item.text(),
                item.policyFacts() == null ? null : new PolicyFactsResponse(
                        item.policyFacts().positions().stream()
                                .map(Enum::name)
                                .collect(java.util.stream.Collectors.toSet()),
                        item.policyFacts().requiresApproval(),
                        item.policyFacts().escalationTrigger()),
                item.contractFacts(),
                item.retrievalReason(),
                new LaneInfoResponse(
                        item.laneInfo().lexicalRank(),
                        item.laneInfo().vectorRank(),
                        item.laneInfo().fusedRank(),
                        item.laneInfo().fusedScore()),
                item.semanticAssessment() == null ? null : new SemanticAssessmentResponse(
                        item.semanticAssessment().judgments(),
                        item.semanticAssessment().decisionSpecId(),
                        item.semanticAssessment().decisionSpecVersion()),
                item.policyCompatibility() == null ? null : item.policyCompatibility().name(),
                item.provenanceNodeIds(),
                item.publicationId(),
                item.publicationNumber() == null ? null : item.publicationNumber(),
                item.applicabilityScope() == null ? null : new ApplicabilityScopeResponse(
                        item.applicabilityScope().contractPackKey(),
                        List.copyOf(item.applicabilityScope().declaredContractTypes()),
                        item.applicabilityScope().partyPerspective(),
                        item.applicabilityScope().paperSource(),
                        item.applicabilityScope().jurisdiction(),
                        item.applicabilityScope().effectiveFrom(),
                        item.applicabilityScope().effectiveTo()));
    }

    @Schema(name = "ApplicabilityScopeResponse")
    public record ApplicabilityScopeResponse(
            String contractPackKey,
            List<String> declaredContractTypes,
            String partyPerspective,
            String paperSource,
            String jurisdiction,
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {
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
            List<RejectedEvidenceResponse> rejectedEvidence,
            List<String> degradation,
            Map<String, Long> stageTimings,
            Map<String, String> modelVersions,
            Long knowledgePinPublicationNumber) {

        @Schema(name = "StageSummaryResponse")
        public record StageSummaryResponse(
                String stage,
                int candidateCount,
                List<String> candidateIds,
                List<Integer> candidateRanks,
                List<String> executionIds) {
        }

        @Schema(name = "RejectedEvidenceResponse")
        public record RejectedEvidenceResponse(
                UUID candidateId,
                String stage,
                String reason) {
        }
    }
}
