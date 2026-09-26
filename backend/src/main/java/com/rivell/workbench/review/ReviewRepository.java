package com.rivell.workbench.review;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for the review aggregate. Implementations live in platform and must
 * enforce organization scoping in SQL; cross-organization access is indistinguishable
 * from missing.
 */
public interface ReviewRepository {

    void create(Review review, ReviewSnapshot snapshot, List<ReviewPlanCheck> checks);

    Optional<Review> find(UUID organizationId, UUID reviewId);

    /**
     * Bounded newest-first list for one contract; implementations read {@code limit + 1}
     * rows so callers can detect truncation.
     */
    List<Review> findReviews(UUID organizationId, UUID contractId, int limit);

    Optional<ReviewSnapshot> findSnapshot(UUID organizationId, UUID reviewId);

    List<ReviewPlanCheck> findPlanChecks(UUID organizationId, UUID reviewId);

    /**
     * Pessimistic row lock serializing human decision writes and finalization for one
     * review; false when the review does not exist in the organization.
     */
    boolean lock(UUID organizationId, UUID reviewId);

    boolean markAnalyzing(UUID organizationId, UUID reviewId, java.time.Instant at);

    boolean markCompleted(UUID organizationId, UUID reviewId, java.time.Instant at);

    boolean markFailed(UUID organizationId, UUID reviewId, String reason, java.time.Instant at);
}
