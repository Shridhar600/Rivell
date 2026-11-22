package com.rivell.workbench.platform.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.rivell.workbench.foundation.JobScheduler;

@Configuration(proxyBeanMethods = false)
class JobSchedulingConfiguration {

    @Bean
    JobScheduler foundationJobScheduler(org.jobrunr.scheduling.JobScheduler delegate) {
        return new JobRunrJobScheduler(delegate);
    }

    /**
     * Review analysis is enqueued, not run inline, and the background worker is off by default,
     * so a review sits in ANALYSIS_QUEUED with no error. Say it once at startup.
     */
    @Bean
    ApplicationListener<ApplicationReadyEvent> backgroundWorkerStartupNotice(
            @Value("${jobrunr.background-job-server.enabled:false}")
            boolean backgroundWorkerEnabled) {
        return event -> {
            var log = LoggerFactory.getLogger(JobSchedulingConfiguration.class);
            if (backgroundWorkerEnabled) {
                log.info("Background job worker is enabled; enqueued review analyses will run.");
            } else {
                log.warn("Background job worker is DISABLED (jobrunr.background-job-server.enabled"
                        + "=false). Review analyses are enqueued but will never run, so a created"
                        + " review will stay in ANALYSIS_QUEUED. Set"
                        + " RIVELL_JOB_WORKER_ENABLED=true to run them.");
            }
        };
    }
}
