package com.rivell.workbench.review.check;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.rivell.workbench.retrieval.EvidenceBundle;
import com.rivell.workbench.retrieval.EvidenceItem;
import com.rivell.workbench.review.CheckExecutionStatus;
import com.rivell.workbench.review.RawReviewSignal;

/**
 * Terminal outcome of one check execution. {@code evidenceItems} are the exact frozen
 * items cited by the outcome's signals (G-M7-1); they are persisted with the execution
 * so Finding evidence resolves from the original run, never from latest knowledge.
 */
public record CheckOutcome(
        CheckExecutionStatus status,
        List<RawReviewSignal> signals,
        Map<String, Object> detail,
        java.util.UUID evidenceTraceId,
        List<EvidenceItem> evidenceItems) {

    public CheckOutcome {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(detail, "detail");
        if (!status.terminal()) {
            throw new IllegalArgumentException("outcome status must be terminal: " + status);
        }
        signals = signals == null ? List.of() : List.copyOf(signals);
        detail = Map.copyOf(detail);
        evidenceItems = evidenceItems == null ? List.of() : List.copyOf(evidenceItems);
    }

    /** Returns a copy carrying the frozen items cited by this outcome's signals. */
    public CheckOutcome withCitedEvidence(List<EvidenceBundle> bundles) {
        var citedIds = new java.util.LinkedHashSet<java.util.UUID>();
        signals.forEach(signal -> citedIds.addAll(signal.evidenceItemIds()));
        var byId = new java.util.LinkedHashMap<java.util.UUID, EvidenceItem>();
        for (var bundle : bundles) {
            for (var item : bundle.items()) {
                byId.putIfAbsent(item.evidenceId(), item);
            }
        }
        var items = citedIds.stream().map(byId::get).filter(Objects::nonNull).toList();
        return new CheckOutcome(status, signals, detail, evidenceTraceId, items);
    }

    public static CheckOutcome completed(List<RawReviewSignal> signals, Map<String, Object> detail) {
        return new CheckOutcome(CheckExecutionStatus.COMPLETED, signals, detail, null, List.of());
    }

    public static CheckOutcome completed(
            List<RawReviewSignal> signals, Map<String, Object> detail,
            java.util.UUID evidenceTraceId) {
        return new CheckOutcome(CheckExecutionStatus.COMPLETED, signals, detail, evidenceTraceId,
                List.of());
    }

    public static CheckOutcome degraded(String reason) {
        return new CheckOutcome(CheckExecutionStatus.DEGRADED, List.of(),
                java.util.Map.of("degradedReason", reason), null, List.of());
    }

    public static CheckOutcome degraded(
            String reason, List<RawReviewSignal> signals, Map<String, Object> detail,
            java.util.UUID evidenceTraceId) {
        var merged = new java.util.LinkedHashMap<String, Object>();
        merged.putAll(detail);
        merged.put("degradedReason", reason);
        return new CheckOutcome(CheckExecutionStatus.DEGRADED, signals, merged, evidenceTraceId,
                List.of());
    }

    public static CheckOutcome skipped(String reason) {
        return new CheckOutcome(CheckExecutionStatus.SKIPPED, List.of(),
                java.util.Map.of("skippedReason", reason), null, List.of());
    }

    public static CheckOutcome failed(String reason) {
        return new CheckOutcome(CheckExecutionStatus.FAILED, List.of(),
                java.util.Map.of("failureReason", reason), null, List.of());
    }
}
