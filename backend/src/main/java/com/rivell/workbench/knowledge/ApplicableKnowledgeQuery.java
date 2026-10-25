package com.rivell.workbench.knowledge;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * The published playbooks and clause libraries that apply to a contract's declared context.
 * The dimensions mirror {@link PublishedKnowledgeQuery}: a knowledge side that declares nothing
 * is a wildcard, and the contract-type rule includes the NDA family and the record's own scope.
 */
public record ApplicableKnowledgeQuery(
        UUID organizationId,
        long asOfPublicationNumber,
        String contractType,
        String contractPackKey,
        String partyPerspective,
        String paperSource,
        String jurisdiction,
        LocalDate effectiveOn) {

    public ApplicableKnowledgeQuery {
        Objects.requireNonNull(organizationId, "organizationId");
        if (asOfPublicationNumber < 0) {
            throw new IllegalArgumentException("asOfPublicationNumber must be >= 0");
        }
        if (contractType == null || contractType.isBlank()) {
            throw new IllegalArgumentException("contractType must not be blank");
        }
        contractType = contractType.trim();
    }
}
