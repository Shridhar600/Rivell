package com.rivell.workbench.review.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Review summary: lifecycle state, active generation, counts, frozen pack/pin, warnings. */
public record ReviewSummaryView(
        UUID id,
        UUID contractId,
        int contractVersionNumber,
        String status,
        String failureReason,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        ReviewRunView activeRun,
        String latestRunStatus,
        int plannedChecks,
        int completedChecks,
        int degradedChecks,
        int failedChecks,
        int signals,
        int findings,
        String contractPackKey,
        String packVersion,
        long knowledgePinNumber,
        List<AnalysisWarningView> analysisWarnings,
        boolean analysisWarningsTruncated) {
}
