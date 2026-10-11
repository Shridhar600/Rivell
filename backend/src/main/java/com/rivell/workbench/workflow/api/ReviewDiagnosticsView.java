package com.rivell.workbench.workflow.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Developer diagnostics for one review run.
 *
 * <p>This is the surface the M10 milestone asks for: enough to answer "what happened, and why is it
 * not moving" without a database session. It is strictly read-only and strictly diagnostic — it
 * carries no contract text, no model prose, and no legal judgement, and it never proposes an action.
 * A finding is reported by count and by the decision recorded against it, not by restating the clause.
 *
 * <p>{@code stalled} is the field that matters operationally. A review whose analysis was enqueued
 * but never picked up, or whose run has been RUNNING far longer than any real run takes, is the
 * single most confusing failure in the product today, because the default configuration has no
 * background worker and nothing says so.
 */
@Schema(description = "Developer diagnostics for one review run. Read-only; contains no contract "
        + "text and no model output.")
public record ReviewDiagnosticsView(
        @Schema(description = "Review the diagnostics describe") UUID reviewId,
        @Schema(description = "Contract version under review") UUID contractId,
        int contractVersionNumber,
        @Schema(description = "Current review status") String reviewStatus,
        @Schema(description = "Failure reason recorded on the review, if any") String reviewFailure,
        @Schema(description = "Whether a background job worker is running. A queued analysis with no "
                + "worker will never start.") boolean backgroundWorkerEnabled,
        AnalysisRunView analysis,
        CountsView findings,
        DecisionsView decisions,
        FinalizationView finalization,
        RedlineView redline,
        @Schema(description = "Operational problems that explain a review making no progress")
        List<String> stalled) {

    @Schema(description = "The latest analysis run")
    public record AnalysisRunView(
            UUID runId,
            int runNumber,
            String status,
            String trigger,
            String pipelineVersion,
            Instant startedAt,
            Instant completedAt,
            Long ageSeconds,
            String failureReason,
            @Schema(description = "Whether the snapshot the review froze still matches the live "
                    + "contract semantics and model configuration")
            Boolean environmentMatchesSnapshot,
            List<CheckView> checks) {
    }

    @Schema(description = "One planned check and how it ended")
    public record CheckView(
            String key,
            @Schema(description = "Clause the check judged, when it has one")
            UUID subjectHeadingNodeId,
            String status,
            Integer signalCount,
            String degradedReason,
            @Schema(description = "Exception type of a FAILED check; no message text")
            String failureType,
            Instant startedAt,
            Instant completedAt,
            Long durationMillis) {
    }

    @Schema(description = "Finding counts, no finding content")
    public record CountsView(
            int total,
            int blocking,
            int reviewRequired,
            int informational) {
    }

    @Schema(description = "Human decision outcomes, which are the natural evaluation labels")
    public record DecisionsView(int accepted, int edited, int rejected, int escalated) {
    }

    @Schema(description = "Finalization state")
    public record FinalizationView(
            boolean finalized,
            UUID changeSetId,
            Instant finalizedAt,
            int approvedChanges) {
    }

    @Schema(description = "Deterministic redline state")
    public record RedlineView(
            String status,
            String outcome,
            String failureCode,
            int changeCount,
            boolean downloadAvailable,
            Long ageSeconds) {
    }
}
