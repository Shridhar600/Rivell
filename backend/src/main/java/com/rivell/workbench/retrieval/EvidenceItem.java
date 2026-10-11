package com.rivell.workbench.retrieval;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.rivell.workbench.taxonomy.ConceptKey;
import com.rivell.workbench.contracts.LegalFact;
import com.rivell.workbench.knowledge.ApplicabilityScope;
import com.rivell.workbench.knowledge.PositionRole;

/**
 * One provenance-backed, claim-addressable evidence item (AD #8.5/#8.6). The exact
 * text is retained verbatim (approved wording is source content). Valid sections
 * depend on the source type: policy items carry policy facts; contract items carry
 * typed LegalFacts; precedent items carry the semantic assessment and its
 * policy-compatibility state. Authority is fixed by the source and never upgraded.
 */
public record EvidenceItem(
        UUID evidenceId,
        AuthorityClass authorityClass,
        String sourceType,
        UUID sourceArtifactId,
        UUID sourceCandidateId,
        UUID sourceContractVersionId,
        UUID sourceClauseId,
        ConceptKey legalConcept,
        ApplicabilityScope applicabilityScope,
        String text,
        PolicyFacts policyFacts,
        List<LegalFact> contractFacts,
        String retrievalReason,
        LaneInfo laneInfo,
        SemanticAssessment semanticAssessment,
        PolicyCompatibility policyCompatibility,
        List<String> provenanceNodeIds,
        UUID publicationId,
        Long publicationNumber,
        PositionRole variantRole) {

    public EvidenceItem {
        Objects.requireNonNull(evidenceId, "evidenceId");
        Objects.requireNonNull(authorityClass, "authorityClass");
        if (sourceType == null || sourceType.isBlank()) {
            throw new IllegalArgumentException("sourceType must not be blank");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Evidence text must not be blank");
        }
        contractFacts = contractFacts == null ? List.of() : List.copyOf(contractFacts);
        if (retrievalReason == null || retrievalReason.isBlank()) {
            throw new IllegalArgumentException("retrievalReason must not be blank");
        }
        laneInfo = laneInfo == null ? LaneInfo.NONE : laneInfo;
        provenanceNodeIds = provenanceNodeIds == null ? List.of() : List.copyOf(provenanceNodeIds);
    }

    /** Non-drafting authority items carry no clause-library variant role. */
    public EvidenceItem(
            UUID evidenceId,
            AuthorityClass authorityClass,
            String sourceType,
            UUID sourceArtifactId,
            UUID sourceCandidateId,
            UUID sourceContractVersionId,
            UUID sourceClauseId,
            ConceptKey legalConcept,
            ApplicabilityScope applicabilityScope,
            String text,
            PolicyFacts policyFacts,
            List<LegalFact> contractFacts,
            String retrievalReason,
            LaneInfo laneInfo,
            SemanticAssessment semanticAssessment,
            PolicyCompatibility policyCompatibility,
            List<String> provenanceNodeIds,
            UUID publicationId,
            Long publicationNumber) {
        this(evidenceId, authorityClass, sourceType, sourceArtifactId, sourceCandidateId,
                sourceContractVersionId, sourceClauseId, legalConcept, applicabilityScope,
                text, policyFacts, contractFacts, retrievalReason, laneInfo,
                semanticAssessment, policyCompatibility, provenanceNodeIds, publicationId,
                publicationNumber, null);
    }

    /**
     * Typed facts of a published playbook rule: position roles, approval requirement,
     * escalation trigger, the role-tagged field values a comparison question asks from,
     * and the topics the rule governs. Machine + human-corrected effective values.
     */
    public record PolicyFacts(
            Set<PositionRole> positions,
            Boolean requiresApproval,
            String escalationTrigger,
            String preferred,
            List<String> fallbacks,
            String prohibited,
            List<String> topicKeys) {

        public PolicyFacts {
            positions = positions == null ? Set.of() : Set.copyOf(positions);
            fallbacks = fallbacks == null ? List.of() : List.copyOf(fallbacks);
            topicKeys = topicKeys == null ? List.of() : List.copyOf(topicKeys);
        }

        public PolicyFacts(
                Set<PositionRole> positions, Boolean requiresApproval, String escalationTrigger) {
            this(positions, requiresApproval, escalationTrigger, null, List.of(), null, List.of());
        }
    }

    /**
     * Bounded Jev assessment of a precedent item (AD #8.3): explicit judgments with
     * their execution reference. No confidence theater — provider facts only.
     */
    public record SemanticAssessment(
            Map<String, Boolean> judgments,
            String decisionSpecId,
            int decisionSpecVersion,
            UUID executionId) {

        public SemanticAssessment {
            judgments = judgments == null ? Map.of() : Map.copyOf(judgments);
        }
    }
}
