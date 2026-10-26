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
     * M10 / DW-020: resolves a prior creation made with the same client-supplied key, so a retry
     * returns the original review instead of freezing a second snapshot and paying for a second
     * analysis. Scoped by organization, so one tenant's key can never resolve another's review.
     */
    Optional<Review> findByClientRequestId(UUID organizationId, UUID clientRequestId);

    Optional<ReviewSnapshot> findSnapshot(UUID organizationId, UUID reviewId);

    List<ReviewPlanCheck> findPlanChecks(UUID organizationId, UUID reviewId);

    /**
     * Reviews whose analysis never started: still {@code ANALYSIS_QUEUED} and created before the
     * given instant. Not organization-scoped because the analysis reconciler is a platform job
     * across every tenant; it only reads the review's own identity and creation time.
     */
    List<Review> findQueuedBefore(java.time.Instant createdBefore);

    /**
     * Reviews still {@code ANALYZING} whose RUNNING run started before the given instant. The
     * database allows one RUNNING run per review, so that run is also the latest. The analysis
     * reconciler re-enqueues them after a database outage lost the job that would have finished the
     * run. Not organization-scoped, like {@link #findQueuedBefore}.
     */
    List<Review> findAnalyzingWithRunStartedBefore(java.time.Instant startedBefore);

    /**
     * Pessimistic row lock serializing human decision writes and finalization for one
     * review; false when the review does not exist in the organization.
     */
    boolean lock(UUID organizationId, UUID reviewId);

    boolean markAnalyzing(UUID organizationId, UUID reviewId, java.time.Instant at);

    boolean markCompleted(UUID organizationId, UUID reviewId, java.time.Instant at);

    boolean markFailed(UUID organizationId, UUID reviewId, String reason, java.time.Instant at);
}
