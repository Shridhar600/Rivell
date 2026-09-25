package com.rivell.workbench.review.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Review summary: lifecycle state, active generation, counts, the frozen snapshot
 * context, coverage limitations, and sanitized analysis warnings. Internal snapshot
 * identities (digest, index/model bindings, pipeline versions) stay internal.
 */
public record ReviewSummaryView(
        UUID id,
        UUID contractId,
        int contractVersionNumber,
        String status,
        FailureView failure,
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
        SnapshotView snapshot,
        List<ReviewCoverageView> coverage,
        List<AnalysisWarningView> analysisWarnings,
        boolean analysisWarningsTruncated) {

    /** Safe, stable failure representation; raw diagnostics stay internal. */
    public record FailureView(String code, String message) {
    }

    /** Frozen review inputs (Pre-M7 decision 3); never reconstructed from live state. */
    public record SnapshotView(
            ProfileView profile,
            String packVersion,
            long knowledgePinNumber,
            LocalDate effectiveOn) {
    }

    public record ProfileView(
            String contractType,
            String ourSideRole,
            String paperSource,
            String jurisdiction,
            String contractPackKey) {
    }
}
