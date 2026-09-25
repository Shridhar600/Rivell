package com.rivell.workbench.platform.jobs;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.rivell.workbench.review.ReviewCreatedEvent;

/**
 * After a review creation transaction commits, enqueue machine analysis. The job is
 * idempotent at the domain boundary, so a duplicate enqueue is safe.
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

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReviewCreated(ReviewCreatedEvent event) {
        jobScheduler.enqueue(() -> handler.handle(event.organizationId(), event.reviewId()));
    }
}
