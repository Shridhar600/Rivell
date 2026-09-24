package com.rivell.workbench.platform.jobs;

import org.springframework.stereotype.Component;

import com.rivell.workbench.retrieval.RetrievalIndexReconciliationService;
import com.rivell.workbench.retrieval.RetrievalProperties;

/**
 * Platform-owned execution scheduling for retrieval index reconciliation
 * (DW-002/DW-012 partition: retrieval owns reconciliation semantics, platform owns
 * execution scheduling). Registers the recurring job only when a cron is
 * configured; execution additionally requires a running background job server.
 * The fixed job id makes re-registration idempotent across restarts.
 */
@Component
class RetrievalReconciliationTrigger {

    private static final String JOB_ID = "retrieval-index-reconciliation";

    RetrievalReconciliationTrigger(
            org.jobrunr.scheduling.JobScheduler jobScheduler,
            RetrievalIndexReconciliationService reconciliationService,
            RetrievalProperties properties) {
        var cron = properties.reconciliationCron();
        if (cron != null && !cron.isBlank()) {
            org.jobrunr.jobs.lambdas.IocJobLambda<RetrievalIndexReconciliationService> job =
                    RetrievalIndexReconciliationService::reconcileFromSchedule;
            jobScheduler.scheduleRecurrently(JOB_ID, cron, job);
        }
    }
}
