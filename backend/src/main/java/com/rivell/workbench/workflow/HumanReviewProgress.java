package com.rivell.workbench.workflow;

import java.util.List;
import java.util.Objects;

/** Human-resolution metrics and finalization readiness for one review. */
public record HumanReviewProgress(
        HumanReviewStatus status,
        int actionableFindings,
        int resolvedFindings,
        int remainingFindings,
        int accepted,
        int edited,
        int rejected,
        int escalated,
        int informationalFindings,
        int informationalDecided,
        boolean readyToFinalize,
        List<FinalizationBlocker> blockers) {

    public HumanReviewProgress {
        Objects.requireNonNull(status, "status");
        blockers = blockers == null ? List.of() : List.copyOf(blockers);
    }
}
