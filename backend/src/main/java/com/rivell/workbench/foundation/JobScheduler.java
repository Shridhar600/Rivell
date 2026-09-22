package com.rivell.workbench.foundation;

public interface JobScheduler {

    JobId enqueue(JobTask task);
}
