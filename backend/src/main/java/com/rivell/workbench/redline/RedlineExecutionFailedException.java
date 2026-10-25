package com.rivell.workbench.redline;

import java.util.UUID;

/** A persisted FAILED execution: the deterministic failure is stable and idempotent. */
public class RedlineExecutionFailedException extends RuntimeException {

    private final UUID executionId;
    private final RedlineFailureCode failureCode;

    public RedlineExecutionFailedException(RedlineExecution execution) {
        super(execution.failureDetail() != null
                ? execution.failureDetail()
                : "Rivell could not generate the redline for this review.");
        this.executionId = execution.id();
        this.failureCode = execution.failureCode();
    }

    public UUID executionId() {
        return executionId;
    }

    public RedlineFailureCode failureCode() {
        return failureCode;
    }
}
