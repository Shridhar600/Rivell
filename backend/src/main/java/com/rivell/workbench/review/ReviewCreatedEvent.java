package com.rivell.workbench.review;

import java.util.Objects;
import java.util.UUID;

/** Published inside the creation transaction; AFTER_COMMIT listeners enqueue analysis. */
public record ReviewCreatedEvent(UUID organizationId, UUID reviewId) {

    public ReviewCreatedEvent {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(reviewId, "reviewId");
    }
}
