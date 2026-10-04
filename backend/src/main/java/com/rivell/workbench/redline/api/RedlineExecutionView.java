package com.rivell.workbench.redline.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Bounded redline execution view: status, provenance, failure, and per-change report. */
public record RedlineExecutionView(
        UUID id,
        UUID reviewId,
        UUID changeSetId,
        UUID contractId,
        int contractVersionNumber,
        int changeSetVersion,
        int attempt,
        String status,
        String outcome,
        String failureCode,
        String failureDetail,
        String rendererVersion,
        String placementStrategyVersion,
        String sourceSha256,
        int changeCount,
        UUID requestedBy,
        Instant startedAt,
        Instant completedAt,
        String artifactFilename,
        Long artifactSizeBytes,
        String artifactSha256,
        boolean downloadAvailable,
        List<RedlineExecutionChangeView> changes) {
}
