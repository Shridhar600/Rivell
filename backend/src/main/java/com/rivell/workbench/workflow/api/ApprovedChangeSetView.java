package com.rivell.workbench.workflow.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Frozen approved-change artifact for one review. */
public record ApprovedChangeSetView(
        UUID id,
        UUID reviewId,
        UUID contractId,
        int contractVersionNumber,
        UUID runId,
        int changeSetVersion,
        UUID finalizedBy,
        Instant finalizedAt,
        List<ApprovedChangeView> changes) {
}
