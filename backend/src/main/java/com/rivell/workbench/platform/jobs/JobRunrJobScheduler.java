package com.rivell.workbench.platform.jobs;

import java.util.Objects;

import com.rivell.workbench.foundation.JobId;
import com.rivell.workbench.foundation.JobScheduler;
import com.rivell.workbench.foundation.JobTask;

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
