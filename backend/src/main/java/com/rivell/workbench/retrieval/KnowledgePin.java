package com.rivell.workbench.retrieval;

/**
 * Pin on the organizational knowledge state a retrieval must respect (AD #8.11).
 * The M7 ReviewSnapshot will supply these pins; M6 accepts them on the request.
 * A null publication number means "resolve the organization's latest publication",
 * and that resolution is recorded in the retrieval trace — never silently assumed.
 * {@link #NONE} (0) is a real frozen pin: no published knowledge was visible when the
 * review was created, and later publications must not appear in its evidence.
 */
public record KnowledgePin(Long publicationNumber) {

    public static final KnowledgePin LATEST = new KnowledgePin(null);
    public static final KnowledgePin NONE = new KnowledgePin(0L);

    public KnowledgePin {
        if (publicationNumber != null && publicationNumber < 0) {
            throw new IllegalArgumentException("Publication number must not be negative");
        }
    }

    public static KnowledgePin at(long publicationNumber) {
        if (publicationNumber < 1) {
            throw new IllegalArgumentException("Publication number must be positive");
        }
        return new KnowledgePin(publicationNumber);
    }

    public static KnowledgePin none() {
        return NONE;
    }

    public boolean pinned() {
        return publicationNumber != null;
    }

    public boolean empty() {
        return publicationNumber != null && publicationNumber == 0L;
    }
}
