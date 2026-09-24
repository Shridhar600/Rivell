package com.rivell.workbench.knowledge;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Emitted after a knowledge publication commits atomically (Architecture Decision
 * #6.10). Carries the stable publication identity so downstream modules (retrieval
 * indexing) can react to the coherent snapshot — never to in-flight review state.
 */
public record KnowledgePublishedEvent(
        UUID organizationId,
        UUID publicationId,
        long publicationNumber,
        List<UUID> candidateIds,
        Map<UUID, UUID> supersededCandidates) {

    public KnowledgePublishedEvent {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(publicationId, "publicationId");
        candidateIds = candidateIds == null ? List.of() : List.copyOf(candidateIds);
        supersededCandidates = supersededCandidates == null
                ? Map.of()
                : Map.copyOf(supersededCandidates);
    }
}
