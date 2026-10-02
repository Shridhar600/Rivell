package com.rivell.workbench.review;

import java.util.UUID;

/**
 * Run resolution for one review on a list page.
 *
 * <p>The counted generation is the latest COMPLETED run, else the latest run; this is the
 * same rule {@code ReviewAnalysisService#progress} and the review summary apply, so a list
 * row and a summary never disagree about which generation a row's counts describe. Both
 * the counted and the latest generation are kept, because a generation newer than the
 * counted one is exactly the "recomputed or degraded since" signal a consumer needs.
 */
public record ReviewListRunState(
        UUID latestCompletedRunId,
        Integer latestCompletedRunNumber,
        String latestCompletedRunStatus,
        UUID latestRunId,
        Integer latestRunNumber,
        String latestRunStatus) {

    /** The generation whose executions and counts this review reports on. */
    public UUID countedRunId() {
        return latestCompletedRunId != null ? latestCompletedRunId : latestRunId;
    }

    /**
     * Whether a generation newer than the counted one exists, mirroring the summary
     * endpoint: true when there is no completed run yet, or when the latest run differs
     * from the latest completed one. False when they are the same generation, because
     * {@code activeRunStatus} already reports that one.
     */
    public boolean hasNewerRunThanCounted() {
        return latestRunId != null
                && (latestCompletedRunId == null || !latestRunId.equals(latestCompletedRunId));
    }
}
