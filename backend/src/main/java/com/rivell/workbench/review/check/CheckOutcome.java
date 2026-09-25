package com.rivell.workbench.review.check;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.rivell.workbench.review.CheckExecutionStatus;
import com.rivell.workbench.review.RawReviewSignal;

/** Terminal outcome of one check execution. */
public record CheckOutcome(
        CheckExecutionStatus status,
        List<RawReviewSignal> signals,
        Map<String, Object> detail,
        java.util.UUID evidenceTraceId) {

    public CheckOutcome {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(detail, "detail");
        if (!status.terminal()) {
            throw new IllegalArgumentException("outcome status must be terminal: " + status);
        }
        signals = signals == null ? List.of() : List.copyOf(signals);
        detail = Map.copyOf(detail);
    }

    public static CheckOutcome completed(List<RawReviewSignal> signals, Map<String, Object> detail) {
        return new CheckOutcome(CheckExecutionStatus.COMPLETED, signals, detail, null);
    }

    public static CheckOutcome completed(
            List<RawReviewSignal> signals, Map<String, Object> detail,
            java.util.UUID evidenceTraceId) {
        return new CheckOutcome(CheckExecutionStatus.COMPLETED, signals, detail, evidenceTraceId);
    }

    public static CheckOutcome degraded(String reason) {
        return new CheckOutcome(CheckExecutionStatus.DEGRADED, List.of(),
                java.util.Map.of("degradedReason", reason), null);
    }

    public static CheckOutcome skipped(String reason) {
        return new CheckOutcome(CheckExecutionStatus.SKIPPED, List.of(),
                java.util.Map.of("skippedReason", reason), null);
    }

    public static CheckOutcome failed(String reason) {
        return new CheckOutcome(CheckExecutionStatus.FAILED, List.of(),
                java.util.Map.of("failureReason", reason), null);
    }
}
