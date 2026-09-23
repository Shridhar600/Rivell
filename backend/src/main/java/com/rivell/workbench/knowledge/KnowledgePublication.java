package com.rivell.workbench.knowledge;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * An atomic, versioned, reproducible organizational knowledge snapshot
 * (Architecture Decision #6.10). Publications are numbered per organization, immutable
 * once created, and are the only route by which candidates gain authority.
 */
public record KnowledgePublication(
        UUID id,
        UUID organizationId,
        long publicationNumber,
        String note,
        List<PublicationItem> items,
        Instant createdAt) {

    public KnowledgePublication {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(organizationId, "organizationId");
        if (publicationNumber < 1) {
            throw new IllegalArgumentException("Publication number must be positive");
        }
        Objects.requireNonNull(items, "items");
        items = List.copyOf(items);
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
