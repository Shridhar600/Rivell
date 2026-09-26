package com.rivell.workbench.workflow.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Human-resolution state of a review: derived status, counts, and readiness. */
public record HumanReviewView(
        UUID reviewId,
        String status,
        String analysisStatus,
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
        List<BlockerView> blockers,
        UUID changeSetId,
        UUID finalizedBy,
        Instant finalizedAt) {
}
