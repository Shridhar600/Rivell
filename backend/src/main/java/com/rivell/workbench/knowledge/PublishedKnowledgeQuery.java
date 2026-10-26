package com.rivell.workbench.knowledge;

import java.time.Instant;
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
 *
 * <p>A query resolves either by concept equality (the default) or, when
 * {@code topicKeys} is present, by topic membership: candidates whose stored topic set
 * intersects the requested topics, ordered by primary-topic match, then shared-topic
 * count, then publication order.
 */
public record PublishedKnowledgeQuery(
        UUID organizationId,
        long asOfPublicationNumber,
        Set<CandidateType> candidateTypes,
        String legalConceptKey,
        Set<String> topicKeys,
        String primaryTopicKey,
        String contractPackKey,
        String contractType,
        String partyPerspective,
        String paperSource,
        String jurisdiction,
        LocalDate effectiveOn,
        int limit,
        /**
         * Keyset cursor for the newest-first order: only rows strictly older than this
         * {@code (created_at, id)} pair are returned, so a pass can walk a corpus larger than
         * one page without re-reading the same newest rows.
         */
        Instant afterCreatedAt,
        UUID afterId) {

    private static final int DEFAULT_LIMIT = 50;

    public PublishedKnowledgeQuery {
        Objects.requireNonNull(organizationId, "organizationId");
        if (asOfPublicationNumber < 1) {
            throw new IllegalArgumentException("asOfPublicationNumber must be positive");
        }
        candidateTypes = candidateTypes == null ? Set.of() : Set.copyOf(candidateTypes);
        topicKeys = topicKeys == null ? Set.of() : Set.copyOf(topicKeys);
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        if ((afterCreatedAt == null) != (afterId == null)) {
            throw new IllegalArgumentException(
                    "a keyset cursor needs both afterCreatedAt and afterId");
        }
        if (afterCreatedAt != null && !topicKeys.isEmpty()) {
            throw new IllegalArgumentException(
                    "a keyset cursor needs the newest-first order, not topic membership");
        }
    }

    /** The same query with a page bound. */
    public PublishedKnowledgeQuery withLimit(int limit) {
        return new PublishedKnowledgeQuery(organizationId, asOfPublicationNumber, candidateTypes,
                legalConceptKey, topicKeys, primaryTopicKey, contractPackKey, contractType,
                partyPerspective, paperSource, jurisdiction, effectiveOn, limit, afterCreatedAt,
                afterId);
    }

    /**
     * The next page of the newest-first order, strictly older than the given row. Topic
     * membership has its own order and does not accept a cursor.
     */
    public PublishedKnowledgeQuery after(Instant createdAt, UUID id) {
        return new PublishedKnowledgeQuery(organizationId, asOfPublicationNumber, candidateTypes,
                legalConceptKey, topicKeys, primaryTopicKey, contractPackKey, contractType,
                partyPerspective, paperSource, jurisdiction, effectiveOn, limit, createdAt, id);
    }

    public static PublishedKnowledgeQuery unpublishedNever(
            UUID organizationId, long asOfPublicationNumber, Set<CandidateType> candidateTypes,
            String legalConceptKey, String contractPackKey, String contractType,
            String partyPerspective, String paperSource, String jurisdiction,
            LocalDate effectiveOn) {
        return new PublishedKnowledgeQuery(organizationId, asOfPublicationNumber, candidateTypes,
                legalConceptKey, Set.of(), null, contractPackKey, contractType, partyPerspective,
                paperSource, jurisdiction, effectiveOn, DEFAULT_LIMIT, null, null);
    }

    /** Topic-membership resolution for policy comparison (design 6a item 2). */
    public static PublishedKnowledgeQuery byTopics(
            UUID organizationId, long asOfPublicationNumber, Set<CandidateType> candidateTypes,
            Set<String> topicKeys, String primaryTopicKey, String contractPackKey,
            String contractType, String partyPerspective, String paperSource,
            String jurisdiction, LocalDate effectiveOn, int limit) {
        return new PublishedKnowledgeQuery(organizationId, asOfPublicationNumber, candidateTypes,
                null, topicKeys, primaryTopicKey, contractPackKey, contractType, partyPerspective,
                paperSource, jurisdiction, effectiveOn, limit, null, null);
    }
}
