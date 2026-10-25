package com.rivell.workbench.platform.jobs;

import org.springframework.stereotype.Component;

/**
 * Registers the recurring review-analysis reconciliation (platform owns execution scheduling, as it
 * does for retrieval reconciliation). The fixed job id makes re-registration idempotent across
 * restarts. Execution additionally needs a running background job server; with the worker off the
 * registration is harmless.
 */
@Component
class ReviewAnalysisReconciliationTrigger {

    static final String JOB_ID = "review-analysis-reconciliation";

    /** Every five minutes: the reconciler only looks at reviews queued longer than that. */
    static final String CRON = "*/5 * * * *";

    ReviewAnalysisReconciliationTrigger(
            org.jobrunr.scheduling.JobScheduler jobScheduler) {
        org.jobrunr.jobs.lambdas.IocJobLambda<ReviewAnalysisReconciler> job =
                ReviewAnalysisReconciler::reconcileFromSchedule;
        jobScheduler.scheduleRecurrently(JOB_ID, CRON, job);
    }
}
