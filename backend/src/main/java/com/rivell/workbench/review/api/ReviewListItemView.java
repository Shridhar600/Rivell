package com.rivell.workbench.review.api;

import java.time.Instant;
import java.util.UUID;

/** Bounded list projection of a review (no counts or warnings). */
public record ReviewListItemView(
        UUID id,
        UUID contractId,
        int contractVersionNumber,
        String status,
        Instant createdAt,
        Instant completedAt) {
}
