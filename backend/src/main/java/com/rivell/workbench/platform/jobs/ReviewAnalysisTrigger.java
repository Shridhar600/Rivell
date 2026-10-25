package com.rivell.workbench.platform.jobs;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.rivell.workbench.review.ReviewCreatedEvent;

/**
 * After a review creation transaction commits, enqueue machine analysis.
 *
 * <p>M10 / B-4: the job id is derived from the work it describes rather than being random, so a
 * duplicated trigger - a retried event, a second listener, a double submit - collapses onto the
 * existing job instead of leaving a second, invisible one in the queue. The work was already
 * idempotent at the domain boundary, so the duplicate was waste and unattributable load rather than
 * corruption, which is exactly why it went unnoticed.
 *
 * <p>The enqueue stays a direct JobRunr call on purpose: JobRunr derives the job signature from the
 * lambda's target method, so routing this through the generic port made every job an anonymous
 * {@code JobTask.run()}. With the dashboard disabled that signature is the only way an operator can
 * tell a queued or failed job from any other, and losing it would trade one blindness for another.
 *
 * <p>The enqueue is registered as an explicit {@code afterCommit} callback rather than a
 * {@code @TransactionalEventListener(AFTER_COMMIT)}: Spring 6.2 runs those listeners in
 * {@code afterCompletion}, where an exception is logged and swallowed. Creation would then report
 * success with no job and the caller could not tell. An explicit callback keeps the post-commit
 * timing and lets the enqueue failure reach the creation caller, who now knows the review exists
 * but its analysis did not start (the reconciler re-enqueues it).
 */
@Component
class ReviewAnalysisTrigger {

    private final org.jobrunr.scheduling.JobScheduler jobScheduler;
    private final ReviewAnalysisJobHandler handler;

    ReviewAnalysisTrigger(
            org.jobrunr.scheduling.JobScheduler jobScheduler,
            ReviewAnalysisJobHandler handler) {
        this.jobScheduler = jobScheduler;
        this.handler = handler;
    }

    @EventListener
    public void onReviewCreated(ReviewCreatedEvent event) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            enqueue(event);
                        }
                    });
            return;
        }
        enqueue(event);
    }

    private void enqueue(ReviewCreatedEvent event) {
        jobScheduler.enqueue(jobIdFor(event.organizationId(), event.reviewId()),
                () -> handler.handle(event.organizationId(), event.reviewId()));
    }

    /** Stable per unit of work: the same review always maps to the same job. */
    static UUID jobIdFor(UUID organizationId, UUID reviewId) {
        return UUID.nameUUIDFromBytes(
                ("rivell-review-analysis:" + organizationId + ":" + reviewId)
                        .getBytes(StandardCharsets.UTF_8));
    }
}
