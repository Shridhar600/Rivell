package com.rivell.workbench.workflow.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Developer diagnostics for one review run.
 *
 * <p>This is the surface the M10 milestone asks for: enough to answer "what happened, and why is it
 * not moving" without a database session. It is strictly read-only and strictly diagnostic — it
 * carries no clause text, no definitions and no model prose (defined-term names only), and no legal
 * judgement, and it never proposes an action. A finding is reported by count and by the decision
 * recorded against it, not by restating the clause.
 *
 * <p>{@code stalled} is the field that matters operationally. A review whose analysis was enqueued
 * but never picked up, or whose run has been RUNNING far longer than any real run takes, is the
 * single most confusing failure in the product today, because the default configuration has no
 * background worker and nothing says so.
 */
@Schema(description = "Developer diagnostics for one review run. Read-only; contains no clause "
        + "text, no definitions and no model output; defined-term names only.")
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
            @Schema(description = "Transient infrastructure failures this run has survived. A "
                    + "running run with a positive count is being retried by JobRunr, not dead.")
            int transientFailures,
            @Schema(description = "Code-owned reason of the last transient failure, if any")
            String lastTransientReason,
            @Schema(description = "Whether the snapshot the review froze still matches the live "
                    + "environment: the contract semantics digest, retrieval index version, "
                    + "embedding model identity and model policy bindings")
            Boolean environmentMatchesSnapshot,
            @Schema(description = "Issues the whole-contract read named whose quote could not be "
                    + "found in the contract, or that were not one issue in one line; absent "
                    + "when the read was not used in this run")
            Integer spottingDropped,
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
            Long durationMillis,
            @Schema(description = "Policy comparison: every judged position with its option "
                    + "probabilities; ids and numbers, never text")
            List<JudgedPositionView> judgedPositions,
            @Schema(description = "Measurement probe that re-asked the comparison: REVERSED, "
                    + "REPEAT or UNAVAILABLE; absent when off")
            String probe,
            @Schema(description = "Uncovered-clause triage probability, when the triage ran")
            Double uncoveredProbability,
            @Schema(description = "Engine v2 route that judged the clause: SEARCH when the "
                    + "position search route ran; absent otherwise")
            String route,
            @Schema(description = "Whether any judged position addresses the clause")
            Boolean addressed,
            @Schema(description = "Positions the search route retrieved, when it ran")
            Integer searchFound,
            @Schema(description = "Whether the search route's comparison failed")
            Boolean searchFailed,
            @Schema(description = "Severity the whole-contract read recorded on this clause: "
                    + "MEDIUM or HIGH; absent when the read named none")
            String spottedSeverity,
            @Schema(description = "Applicable positions the omission check judged")
            Integer omissionCandidates,
            @Schema(description = "Contract clauses the omission check searched")
            Integer searchedClauses,
            @Schema(description = "Omission presence probability per candidate position id")
            Map<String, Double> presenceProbabilities,
            @Schema(description = "Count of positions the omission search addressed")
            Integer addressedBySearch,
            @Schema(description = "Count of positions the omission search flagged")
            Integer flaggedBySearch,
            @Schema(description = "Engine v2: defined terms whose definitions the judge read for "
                    + "this clause; absent when none")
            List<String> definitionTerms) {
    }

    @Schema(description = "One judged playbook position: ids, categories and probabilities only")
    public record JudgedPositionView(
            String positionKey,
            String positionCandidateId,
            String category,
            Double flagProbability,
            Boolean flagged,
            Map<String, Double> probabilities,
            String probeCategory,
            Boolean probeFlagged,
            Map<String, Double> probeProbabilities,
            String route,
            String secondOpinionOutcome,
            String secondOpinionCategory,
            @Schema(description = "SEARCH when the text search, not a topic, found this position; "
                    + "absent otherwise")
            String foundBy) {
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
