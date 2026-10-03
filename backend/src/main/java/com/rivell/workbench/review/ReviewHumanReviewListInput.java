package com.rivell.workbench.review;

import java.util.UUID;

/**
 * Everything the human-review resolver needs about one review on a list page.
 *
 * <p>The caller resolves lifecycle meaning — which generation is counted, whether
 * analysis completed, how many checks were planned and completed, and whether the review
 * is finalized — and the read port supplies only the findings, decisions and frozen
 * structure those decisions refer to.
 */
public record ReviewHumanReviewListInput(
        UUID reviewId,
        UUID countedRunId,
        boolean analysisComplete,
        int plannedChecks,
        int completedChecks,
        boolean finalized,
        UUID contractId,
        int contractVersionNumber) {
}
