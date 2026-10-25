package com.rivell.workbench.platform.jobs;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import org.jobrunr.jobs.states.StateName;
import org.jobrunr.storage.JobNotFoundException;
import org.jobrunr.storage.StorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.rivell.workbench.review.Review;
import com.rivell.workbench.review.ReviewRepository;

/**
 * Recovery for a review whose analysis was never enqueued.
 *
 * <p>Creation commits the review and then publishes {@code ReviewCreatedEvent}; the after-commit
 * listener is a second write that can fail or be lost with the process. Nothing else would pick
 * such a review up, so it sits in {@code ANALYSIS_QUEUED} forever. This reconciler re-enqueues
 * reviews that have stayed queued past the grace period.
 *
 * <p>The analysis job id is derived from the review (see {@link ReviewAnalysisTrigger#jobIdFor}),
 * and JobRunr skips an enqueue whose id already exists, so re-enqueueing is idempotent: a review
 * with a live job is left alone. A job that failed for good still holds its id in storage, so it is
 * removed before the enqueue; otherwise that review could never be recovered.
 */
@Component
public class ReviewAnalysisReconciler {

    /** Long enough for a normal creation and enqueue to finish, short enough to self-heal. */
    static final Duration STALE_AFTER = Duration.ofMinutes(5);

    private static final Logger LOG = LoggerFactory.getLogger(ReviewAnalysisReconciler.class);

    private final ReviewRepository reviewRepository;
    private final org.jobrunr.scheduling.JobScheduler jobScheduler;
    private final ReviewAnalysisJobHandler handler;
    private final StorageProvider storageProvider;
    private final Clock clock;

    ReviewAnalysisReconciler(
            ReviewRepository reviewRepository,
            org.jobrunr.scheduling.JobScheduler jobScheduler,
            ReviewAnalysisJobHandler handler,
            StorageProvider storageProvider,
            Clock clock) {
        this.reviewRepository = reviewRepository;
        this.jobScheduler = jobScheduler;
        this.handler = handler;
        this.storageProvider = storageProvider;
        this.clock = clock;
    }

    /** Scheduled entry point; JobRunr resolves this bean for the recurring job. */
    public void reconcileFromSchedule() {
        reconcile();
    }

    /** Re-enqueues every stale queued review that has no usable job and returns that count. */
    int reconcile() {
        var reviews = reviewRepository.findQueuedBefore(clock.instant().minus(STALE_AFTER));
        var enqueued = 0;
        for (var review : reviews) {
            try {
                if (enqueue(review)) {
                    enqueued++;
                }
            } catch (RuntimeException e) {
                // one review must not stop the pass for every other tenant; the next pass retries
                LOG.warn("Unable to re-enqueue the analysis of review {}; the next pass retries",
                        review.id(), e);
            }
        }
        return enqueued;
    }

    private boolean enqueue(Review review) {
        var jobId = ReviewAnalysisTrigger.jobIdFor(review.organizationId(), review.id());
        if (hasExecutableJob(jobId)) {
            return false;
        }
        jobScheduler.enqueue(jobId, () -> handler.handle(review.organizationId(), review.id()));
        LOG.info("Re-enqueued analysis job {} for review {} in organization {}, queued since {}",
                jobId, review.id(), review.organizationId(), review.createdAt());
        return true;
    }

    /**
     * A job that can still run owns this review. A terminal failure or a deleted job cannot, and its
     * id still blocks JobRunr's enqueue, so it is removed to let this pass replace it.
     */
    private boolean hasExecutableJob(UUID jobId) {
        StateName state;
        try {
            state = storageProvider.getJobById(jobId).getState();
        } catch (JobNotFoundException missing) {
            return false;
        }
        if (state == StateName.FAILED || state == StateName.DELETED) {
            storageProvider.deletePermanently(jobId);
            return false;
        }
        return true;
    }
}
