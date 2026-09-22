package com.rivell.workbench.platform.jobs;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.rivell.workbench.foundation.JobScheduler;

@Configuration(proxyBeanMethods = false)
class JobSchedulingConfiguration {

    @Bean
    JobScheduler foundationJobScheduler(org.jobrunr.scheduling.JobScheduler delegate) {
        return new JobRunrJobScheduler(delegate);
    }
}
