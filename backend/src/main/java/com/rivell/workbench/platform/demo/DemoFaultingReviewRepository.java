package com.rivell.workbench.platform.demo;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.rivell.workbench.review.Review;
import com.rivell.workbench.review.ReviewPlanCheck;
import com.rivell.workbench.review.ReviewRepository;
import com.rivell.workbench.review.ReviewSnapshot;

/**
 * Delegating review repository used only by the demo analysis service. It throws a real
 * RuntimeException from the plan-check read for a marked review, which the production
 * analysis orchestration catches and persists as a genuine FAILED run and review. Every
 * other call passes straight through.
 */
final class DemoFaultingReviewRepository implements ReviewRepository {

    private final ReviewRepository delegate;
    private final DemoAnalysisFaultPolicy policy;

    DemoFaultingReviewRepository(ReviewRepository delegate, DemoAnalysisFaultPolicy policy) {
        this.delegate = delegate;
        this.policy = policy;
    }

    @Override
    public void create(Review review, ReviewSnapshot snapshot, List<ReviewPlanCheck> checks) {
        delegate.create(review, snapshot, checks);
    }

    @Override
    public Optional<Review> find(UUID organizationId, UUID reviewId) {
        return delegate.find(organizationId, reviewId);
    }

    @Override
    public Optional<Review> findByClientRequestId(UUID organizationId, UUID clientRequestId) {
        return delegate.findByClientRequestId(organizationId, clientRequestId);
    }

    @Override
    public Optional<ReviewSnapshot> findSnapshot(UUID organizationId, UUID reviewId) {
        return delegate.findSnapshot(organizationId, reviewId);
    }

    @Override
    public List<ReviewPlanCheck> findPlanChecks(UUID organizationId, UUID reviewId) {
        if (policy.shouldFail(reviewId)) {
            throw new DemoAnalysisFaultPolicy.ReviewPlanUnavailableFailure();
        }
        return delegate.findPlanChecks(organizationId, reviewId);
    }

    @Override
    public List<Review> findQueuedBefore(Instant createdBefore) {
        return delegate.findQueuedBefore(createdBefore);
    }

    @Override
    public List<Review> findAnalyzingWithRunStartedBefore(Instant startedBefore) {
        return delegate.findAnalyzingWithRunStartedBefore(startedBefore);
    }

    @Override
    public boolean lock(UUID organizationId, UUID reviewId) {
        return delegate.lock(organizationId, reviewId);
    }

    @Override
    public boolean markAnalyzing(UUID organizationId, UUID reviewId, Instant at) {
        return delegate.markAnalyzing(organizationId, reviewId, at);
    }

    @Override
    public boolean markCompleted(UUID organizationId, UUID reviewId, Instant at) {
        return delegate.markCompleted(organizationId, reviewId, at);
    }

    @Override
    public boolean markFailed(UUID organizationId, UUID reviewId, String reason, Instant at) {
        return delegate.markFailed(organizationId, reviewId, reason, at);
    }
}
