package com.rivell.workbench.review;

public enum CheckExecutionStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    DEGRADED,
    FAILED,
    SKIPPED;

    public boolean terminal() {
        return this == COMPLETED || this == DEGRADED || this == FAILED || this == SKIPPED;
    }
}
