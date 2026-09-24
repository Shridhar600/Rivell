package com.rivell.workbench.retrieval;

import java.util.Objects;

/**
 * Pin on the organizational knowledge state a retrieval must respect (AD #8.11).
 * The M7 ReviewSnapshot will supply these pins; M6 accepts them on the request.
 * A null publication number means "resolve the organization's latest publication",
 * and that resolution is recorded in the retrieval trace — never silently assumed.
 */
public record KnowledgePin(Long publicationNumber) {

    public static final KnowledgePin LATEST = new KnowledgePin(null);

    public KnowledgePin {
        if (publicationNumber != null && publicationNumber < 1) {
            throw new IllegalArgumentException("Publication number must be positive");
        }
    }

    public static KnowledgePin at(long publicationNumber) {
        Objects.requireNonNull(publicationNumber, "publicationNumber");
        return new KnowledgePin(publicationNumber);
    }

    public boolean pinned() {
        return publicationNumber != null;
    }
}
