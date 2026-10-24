package com.rivell.workbench.platform.jobs;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import org.jobrunr.jobs.Job;
import org.jobrunr.jobs.JobDetails;
import org.jobrunr.jobs.filters.JobServerFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.rivell.workbench.review.ReviewAnalysisRepository;
import com.rivell.workbench.review.ReviewRepository;
import com.rivell.workbench.review.ReviewStatus;
import com.rivell.workbench.review.RunStatus;

/**
 * JobRunr has given up on a review analysis job. A database outage can prevent the service from
 * writing its transient-failure counter at all, so the retry limit is the last reliable signal that
 * nobody will run this review again: mark the RUNNING run and the ANALYZING review terminally failed
 * instead of leaving them looking alive forever.
 *
 * <p>If the latest run is already COMPLETED (a crash artefact between the run's completion and the
 * review's status), the review is healed to COMPLETED instead of failing a finished generation.
 *
 * <p>Deliberately narrow: only jobs for {@link ReviewAnalysisJobHandler#handle}, and only rows still
 * in their in-progress state, so a second callback (or a job that already failed terminally in
 * {@code analyze}) is a no-op.
 */
@Component
public class ReviewAnalysisRetryExhaustedFilter implements JobServerFilter {

    static final String FAILURE_REASON = "analysis_retries_exhausted";

    private static final Logger LOG =
            LoggerFactory.getLogger(ReviewAnalysisRetryExhaustedFilter.class);

    private final ReviewRepository reviewRepository;
    private final ReviewAnalysisRepository analysisRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public ReviewAnalysisRetryExhaustedFilter(
            ReviewRepository reviewRepository,
            ReviewAnalysisRepository analysisRepository,
            TransactionTemplate transactionTemplate,
            Clock clock) {
        this.reviewRepository = reviewRepository;
        this.analysisRepository = analysisRepository;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    @Override
    public void onFailedAfterRetries(Job job) {
        var target = reviewTarget(job);
        if (target.isEmpty()) {
            return;
        }
        var organizationId = target.get().organizationId();
        var reviewId = target.get().reviewId();
        try {
            transactionTemplate.executeWithoutResult(status -> {
                var now = clock.instant();
                var running = analysisRepository.findRunningRun(organizationId, reviewId);
                if (running.isPresent()) {
                    analysisRepository.markRunFailed(organizationId, running.get().id(),
                            FAILURE_REASON, now);
                    failReviewIfInProgress(organizationId, reviewId, now);
                    return;
                }
                var latest = analysisRepository.findLatestRun(organizationId, reviewId);
                if (latest.isPresent() && latest.get().status() == RunStatus.COMPLETED) {
                    // A completed generation with a still-ANALYZING review is a crash/retry
                    // artefact, not a failed analysis: heal the review instead of discarding the
                    // completed run.
                    reviewRepository.find(organizationId, reviewId)
                            .filter(review -> review.status() == ReviewStatus.ANALYZING)
                            .ifPresent(review -> reviewRepository.markCompleted(
                                    organizationId, reviewId, now));
                    return;
                }
                failReviewIfInProgress(organizationId, reviewId, now);
            });
        } catch (RuntimeException e) {
            // The job is already failed; the filter is best-effort and must not break the state
            // transition JobRunr is performing.
            LOG.error("Unable to mark review analysis run/review {} failed after JobRunr exhausted"
                    + " its retries", reviewId, e);
        }
    }

    private void failReviewIfInProgress(UUID organizationId, UUID reviewId, java.time.Instant now) {
        reviewRepository.find(organizationId, reviewId)
                .filter(review -> inProgress(review.status()))
                .ifPresent(review -> reviewRepository.markFailed(organizationId, reviewId,
                        FAILURE_REASON, now));
    }

    private static boolean inProgress(ReviewStatus status) {
        return status == ReviewStatus.ANALYZING || status == ReviewStatus.ANALYSIS_QUEUED;
    }

    /**
     * The job details are JobRunr's record of the lambda target, not a caller-supplied marker: the
     * class and method must be the review analysis handler's, and the two parameters must be the
     * organization and review ids it was enqueued with.
     */
    private static Optional<Target> reviewTarget(Job job) {
        JobDetails details;
        try {
            details = job.getJobDetails();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        if (details == null
                || !ReviewAnalysisJobHandler.class.getName().equals(details.getClassName())
                || !"handle".equals(details.getMethodName())) {
            return Optional.empty();
        }
        Object[] parameters;
        try {
            parameters = details.getJobParameterValues();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        if (parameters.length != 2
                || !(parameters[0] instanceof UUID organizationId)
                || !(parameters[1] instanceof UUID reviewId)) {
            return Optional.empty();
        }
        return Optional.of(new Target(organizationId, reviewId));
    }

    private record Target(UUID organizationId, UUID reviewId) {
    }
}
