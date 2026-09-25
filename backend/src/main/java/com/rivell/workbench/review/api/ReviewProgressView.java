package com.rivell.workbench.review.api;

import java.util.List;

/** Typed progress over the counted run, mirroring the domain progress read model. */
public record ReviewProgressView(
        String reviewStatus,
        int plannedChecks,
        int pendingChecks,
        int runningChecks,
        int completedChecks,
        int degradedChecks,
        int failedChecks,
        int skippedChecks,
        int signals,
        int findings,
        String latestRunStatus,
        List<ReviewCoverageView> coverage,
        List<AnalysisWarningView> analysisWarnings,
        boolean analysisWarningsTruncated) {
}
