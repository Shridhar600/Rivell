package com.rivell.workbench.knowledge;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Bounded retrieval query over published knowledge (Architecture Decision #8.1,
 * DW-010). Request-side scope dimensions come from the human-declared review profile;
 * knowledge rows with absent dimensions are wildcards; both-present dimensions must
 * match exactly. Results are limited to candidates visible at the pinned publication
 * number (supersession-aware). Never hydrates the whole published set.
 */
public record PublishedKnowledgeQuery(
        UUID organizationId,
        long asOfPublicationNumber,
        Set<CandidateType> candidateTypes,
        String legalConceptKey,
        String contractPackKey,
        String contractType,
        String partyPerspective,
        String paperSource,
        String jurisdiction,
        LocalDate effectiveOn,
        int limit) {

    private static final int DEFAULT_LIMIT = 50;

    public PublishedKnowledgeQuery {
        Objects.requireNonNull(organizationId, "organizationId");
        if (asOfPublicationNumber < 1) {
            throw new IllegalArgumentException("asOfPublicationNumber must be positive");
        }
        candidateTypes = candidateTypes == null ? Set.of() : Set.copyOf(candidateTypes);
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
    }

    public static PublishedKnowledgeQuery unpublishedNever(
            UUID organizationId, long asOfPublicationNumber, Set<CandidateType> candidateTypes,
            String legalConceptKey, String contractPackKey, String contractType,
            String partyPerspective, String paperSource, String jurisdiction,
            LocalDate effectiveOn) {
        return new PublishedKnowledgeQuery(organizationId, asOfPublicationNumber, candidateTypes,
                legalConceptKey, contractPackKey, contractType, partyPerspective, paperSource,
                jurisdiction, effectiveOn, DEFAULT_LIMIT);
    }
}
