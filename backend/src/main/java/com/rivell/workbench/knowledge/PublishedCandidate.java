package com.rivell.workbench.knowledge;

import java.util.Objects;
import java.util.UUID;

/**
 * One candidate as resolved by the as-of published-knowledge lookup, together with
 * the publication that made it visible (the unique ADD publication). Retrieval uses
 * this to attach publication provenance to evidence items.
 */
public record PublishedCandidate(
        KnowledgeCandidate candidate,
        UUID publicationId,
        long publicationNumber) {

    public PublishedCandidate {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(publicationId, "publicationId");
        if (publicationNumber < 1) {
            throw new IllegalArgumentException("publicationNumber must be positive");
        }
    }
}
