package com.rivell.workbench.retrieval;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import com.rivell.workbench.taxonomy.ConceptKey;
import com.rivell.workbench.contracts.ReviewProfile;

/**
 * Typed retrieval intent (Architecture Decision #8): the planner routes this request
 * through authority lanes; nothing here is free text. Applicability inputs come from
 * the human-declared ReviewProfile carried by the contract's enrichment, never from
 * machine-inferred metadata. Future M7 ReviewSnapshot can construct this request.
 */
public record EvidenceRequest(
        UUID organizationId,
        RetrievalPurpose purpose,
        ConceptKey legalConcept,
        UUID contractId,
        int versionNumber,
        UUID clauseId,
        String clauseText,
        ReviewProfile reviewProfile,
        LocalDate effectiveOn,
        KnowledgePin knowledgePin) {

    public EvidenceRequest {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(purpose, "purpose");
        if (purpose == RetrievalPurpose.CLAUSE_REVIEW) {
            Objects.requireNonNull(legalConcept, "legalConcept for CLAUSE_REVIEW");
            Objects.requireNonNull(contractId, "contractId for CLAUSE_REVIEW");
            if (versionNumber < 1) {
                throw new IllegalArgumentException("versionNumber must be positive");
            }
            Objects.requireNonNull(clauseId, "clauseId for CLAUSE_REVIEW");
            Objects.requireNonNull(reviewProfile, "reviewProfile for CLAUSE_REVIEW");
            if (clauseText == null || clauseText.isBlank()) {
                throw new IllegalArgumentException("clauseText must not be blank");
            }
        }
        effectiveOn = effectiveOn == null ? LocalDate.now() : effectiveOn;
        knowledgePin = knowledgePin == null ? KnowledgePin.LATEST : knowledgePin;
    }

    public static EvidenceRequest forClauseReview(
            UUID organizationId,
            ConceptKey legalConcept,
            UUID contractId,
            int versionNumber,
            UUID clauseId,
            String clauseText,
            ReviewProfile reviewProfile,
            LocalDate effectiveOn,
            KnowledgePin knowledgePin) {
        return new EvidenceRequest(organizationId, RetrievalPurpose.CLAUSE_REVIEW,
                legalConcept, contractId, versionNumber, clauseId, clauseText,
                reviewProfile, effectiveOn, knowledgePin);
    }
}
