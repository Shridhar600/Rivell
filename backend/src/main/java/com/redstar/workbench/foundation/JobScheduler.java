package com.redstar.workbench.foundation;

public interface JobScheduler {

    JobId enqueue(JobTask task);
}
