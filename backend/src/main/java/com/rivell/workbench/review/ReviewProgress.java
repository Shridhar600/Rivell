package com.rivell.workbench.review;

import java.util.Objects;

/** Derived progress over the active run's check executions. */
public record ReviewProgress(
        ReviewStatus reviewStatus,
        int plannedChecks,
        int pendingChecks,
        int runningChecks,
        int completedChecks,
        int degradedChecks,
        int failedChecks,
        int skippedChecks,
        int signals,
        int findings) {

    public ReviewProgress {
        Objects.requireNonNull(reviewStatus, "reviewStatus");
    }
}
