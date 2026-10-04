package com.rivell.workbench.review.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.rivell.workbench.review.ProposedChangeCounts;

/**
 * Review summary: lifecycle state, active generation, counts, the frozen snapshot
 * context, coverage limitations, and sanitized analysis warnings. Internal snapshot
 * identities (digest, index/model bindings, pipeline versions) stay internal.
 *
 * <p>{@code document} describes the uploaded file behind the pinned contract version, so a
 * lifecycle surface can name the document it is about without a second request or a fallback
 * to the contract list. {@code proposedChanges} counts Rivell's own proposals for the counted
 * generation and is orientation only; it is not a count of approved changes.
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
        ProposedChangeCounts proposedChanges,
        DocumentView document,
        SnapshotView snapshot,
        List<ReviewCoverageView> coverage,
        List<AnalysisWarningView> analysisWarnings,
        boolean analysisWarningsTruncated) {

    /** Safe, stable failure representation; raw diagnostics stay internal. */
    public record FailureView(String code, String message) {
    }

    /**
     * The pinned version's uploaded document.
     *
     * <p>Reported from the version the review froze. A contract that has since received a
     * newer version does not change these values, and {@code uploadedAt} is the version's
     * ingestion time rather than the review's creation time.
     */
    public record DocumentView(
            String contractFilename,
            java.time.Instant uploadedAt,
            long sizeBytes) {
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
