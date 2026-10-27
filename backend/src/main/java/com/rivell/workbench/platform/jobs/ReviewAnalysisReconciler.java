package com.rivell.workbench.platform.jobs;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jobrunr.jobs.states.StateName;
import org.jobrunr.storage.JobNotFoundException;
import org.jobrunr.storage.StorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.rivell.workbench.review.Review;
import com.rivell.workbench.review.ReviewEngineProperties;
import com.rivell.workbench.review.ReviewRepository;

/**
 * Recovery for a review whose analysis was never enqueued.
 *
 * <p>Creation commits the review and then publishes {@code ReviewCreatedEvent}; the after-commit
 * listener is a second write that can fail or be lost with the process. Nothing else would pick
 * such a review up, so it sits in {@code ANALYSIS_QUEUED} forever. This reconciler re-enqueues
 * reviews that have stayed queued past the grace period.
 *
 * <p>A database outage can also outlast JobRunr's retries: the retry-exhausted filter cannot write
 * its failure while the database is down, so the review stays {@code ANALYZING} and its run
 * {@code RUNNING}. Once that run is past its whole analysis budget, this reconciler re-enqueues the
 * review too, so the run resumes and completes; checks the spent budget left unstarted degrade as
 * usual.
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
    private final ReviewEngineProperties reviewEngineProperties;
    private final Clock clock;

    ReviewAnalysisReconciler(
            ReviewRepository reviewRepository,
            org.jobrunr.scheduling.JobScheduler jobScheduler,
            ReviewAnalysisJobHandler handler,
            StorageProvider storageProvider,
            ReviewEngineProperties reviewEngineProperties,
            Clock clock) {
        this.reviewRepository = reviewRepository;
        this.jobScheduler = jobScheduler;
        this.handler = handler;
        this.storageProvider = storageProvider;
        this.reviewEngineProperties = reviewEngineProperties;
        this.clock = clock;
    }

    /** Scheduled entry point; JobRunr resolves this bean for the recurring job. */
    public void reconcileFromSchedule() {
        reconcile();
    }

    /** Re-enqueues every stale queued or stuck analyzing review that has no usable job. */
    int reconcile() {
        var now = clock.instant();
        var enqueued = enqueueAll(reviewRepository.findQueuedBefore(now.minus(STALE_AFTER)));
        enqueued += enqueueAll(reviewRepository.findAnalyzingWithRunStartedBefore(
                stuckRunStartedBefore(now)));
        return enqueued;
    }

    private int enqueueAll(List<Review> reviews) {
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

    /**
     * A run still RUNNING after its whole analysis budget has elapsed cannot finish on its own.
     * The margin is one reconciliation interval, and the attempt cap keeps a pathological setting
     * from overflowing the grace period.
     */
    private Instant stuckRunStartedBefore(Instant now) {
        var attempts = Math.min(reviewEngineProperties.maxTransientFailures() + 1L, 1000L);
        return now.minus(reviewEngineProperties.analysisBudget().multipliedBy(attempts))
                .minus(STALE_AFTER);
    }

    private boolean enqueue(Review review) {
        var jobId = ReviewAnalysisTrigger.jobIdFor(review.organizationId(), review.id());
        if (hasExecutableJob(jobId)) {
            return false;
        }
        jobScheduler.enqueue(jobId, () -> handler.handle(review.organizationId(), review.id()));
        LOG.info("Re-enqueued analysis job {} for review {} in organization {}, created {}",
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
