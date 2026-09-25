package com.rivell.workbench.review;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Execution state of one plan check within one analysis run. */
public record ReviewCheckExecution(
        UUID id,
        UUID organizationId,
        UUID reviewId,
        UUID runId,
        UUID planCheckId,
        CheckExecutionStatus status,
        UUID evidenceTraceId,
        Map<String, Object> detail,
        int signalCount,
        Instant startedAt,
        Instant completedAt) {

    public ReviewCheckExecution {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(reviewId, "reviewId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(planCheckId, "planCheckId");
        Objects.requireNonNull(status, "status");
        detail = detail == null ? Map.of() : Map.copyOf(detail);
        if (signalCount < 0) {
            throw new IllegalArgumentException("signalCount must be >= 0");
        }
    }

    public static ReviewCheckExecution pending(
            UUID id, UUID organizationId, UUID reviewId, UUID runId, UUID planCheckId) {
        return new ReviewCheckExecution(id, organizationId, reviewId, runId, planCheckId,
                CheckExecutionStatus.PENDING, null, Map.of(), 0, null, null);
    }
}
