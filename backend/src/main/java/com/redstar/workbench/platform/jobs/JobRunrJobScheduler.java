package com.redstar.workbench.platform.jobs;

import java.util.Objects;

import com.redstar.workbench.foundation.JobId;
import com.redstar.workbench.foundation.JobScheduler;
import com.redstar.workbench.foundation.JobTask;

public final class JobRunrJobScheduler implements JobScheduler {

    private final org.jobrunr.scheduling.JobScheduler delegate;

    public JobRunrJobScheduler(org.jobrunr.scheduling.JobScheduler delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public JobId enqueue(JobTask task) {
        Objects.requireNonNull(task, "task");
        return new JobId(delegate.enqueue(task::run).asUUID());
    }
}
