package com.rivell.workbench.review.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One row of the review list.
 *
 * <p>Identity and the pinned contract version come from the review. The lifecycle,
 * generation and progress numbers are the same truth {@link ReviewSummaryView} reports,
 * aggregated for the whole page at once, so a rendered row never needs a follow-up
 * request to know what state its review is in. Human-review state, finalization and
 * redline state are resolved for the whole page the same way, so a list can be filtered
 * and rendered without a request per row.
 *
 * <p>The pinned contract version's filename and ingestion time are included because a row
 * without them cannot identify its own work. Internal snapshot identities, run
 * identifiers, model bindings and failure diagnostics are deliberately absent: a list row
 * is not an addressable resource.
 */
public record ReviewListItemView(
        UUID id,
        UUID contractId,
        int contractVersionNumber,
        String contractFilename,
        Instant uploadedAt,
        String status,
        ReviewSummaryView.FailureView failure,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        Integer activeRunNumber,
        String activeRunStatus,
        String latestRunStatus,
        int plannedChecks,
        int completedChecks,
        int degradedChecks,
        int failedChecks,
        int signals,
        int findings,
        ContextView context,
        HumanReviewSummaryView humanReview,
        FinalizationSummaryView finalization,
        RedlineSummaryView redline) {

    /**
     * The human-declared context this review was created against, as frozen at creation.
     *
     * <p>Reported under its own domain values and never inferred. A side and a paper source
     * are genuinely optional and absent when the reviewer did not declare them.
     */
    public record ContextView(
            String contractType,
            String ourSideRole,
            String paperSource,
            String jurisdiction) {
    }

    /**
     * Human-resolution state, named and counted exactly as the human-review read model
     * reports it so the two can never disagree.
     *
     * <p>Readiness is the server's own assessment and there is deliberately no progress
     * percentage: the domain has no such number, and a synthesized one would misrepresent a
     * review that is legitimately blocked. {@code blockers} names the reasons finalization
     * is unavailable, so an unresolved finding is distinguishable from an active
     * escalation without the consumer inferring either.
     */
    public record HumanReviewSummaryView(
            String status,
            int actionableFindings,
            int resolvedFindings,
            int remainingFindings,
            int escalated,
            boolean readyToFinalize,
            List<String> blockers) {

        public HumanReviewSummaryView {
            blockers = blockers == null ? List.of() : List.copyOf(blockers);
        }
    }

    /**
     * Present only once the review is finalized.
     *
     * <p>Finalization is a separate fact from analysis completion. {@code approvedChanges}
     * is the size of the frozen change set, not the finding count, so a rejected finding
     * contributes nothing and an accepted one contributes exactly one executable change.
     */
    public record FinalizationSummaryView(Instant finalizedAt, int approvedChanges) {
    }

    /**
     * Present only once a redline has been requested, and absent when none ever was.
     *
     * <p>{@code verifiedChangeCount} is reported only for a completed execution that really
     * produced a redline. A failed execution can hold changes that verified individually, so
     * its partial count is withheld rather than presented as a verified total, and an
     * execution with nothing to redline verified nothing so carries no count. Failure
     * diagnostics stay on the review's own surfaces.
     */
    public record RedlineSummaryView(
            String status,
            String outcome,
            Integer verifiedChangeCount,
            boolean downloadAvailable,
            Instant completedAt) {
    }
}
