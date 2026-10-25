package com.rivell.workbench.redline.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.rivell.workbench.redline.RedlineExecutionStatus;
import com.rivell.workbench.redline.RedlineFailureCode;
import com.rivell.workbench.redline.RedlineOutcome;

import io.swagger.v3.oas.annotations.media.Schema;

/** Bounded redline execution view: status, provenance, failure, and per-change report. */
public record RedlineExecutionView(
        UUID id,
        UUID reviewId,
        UUID changeSetId,
        UUID contractId,
        int contractVersionNumber,
        int changeSetVersion,
        int attempt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) RedlineExecutionStatus status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        RedlineOutcome outcome,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        RedlineFailureCode failureCode,
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
