package com.rivell.workbench.redline;

import java.util.Optional;
import java.util.UUID;

/** Persistence port for frozen approved-change sets (organization-scoped). */
public interface ApprovedChangeSetRepository {

    Optional<ApprovedChangeSet> findByReview(UUID organizationId, UUID reviewId);

    /**
     * Freezes the set and its changes atomically. Returns false when a set already
     * exists for the review (idempotent replay); the caller then reads the winner.
     */
    boolean freeze(ApprovedChangeSet set);
}
