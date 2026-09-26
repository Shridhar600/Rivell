package com.rivell.workbench.redline;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Deterministic execution plan for one ApprovedChangeSet: resolved document anchors and
 * exact approved text, ready for OOXML rendering. No legal reasoning, no model input.
 */
public record DocumentExecutionPlan(
        UUID changeSetId,
        int changeSetVersion,
        String rendererVersion,
        String placementStrategyVersion,
        String author,
        Instant executedAt,
        String sourceSha256,
        List<PlannedDocumentOperation> operations) {

    public DocumentExecutionPlan {
        Objects.requireNonNull(changeSetId, "changeSetId");
        Objects.requireNonNull(rendererVersion, "rendererVersion");
        Objects.requireNonNull(placementStrategyVersion, "placementStrategyVersion");
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(executedAt, "executedAt");
        Objects.requireNonNull(sourceSha256, "sourceSha256");
        operations = operations == null ? List.of() : List.copyOf(operations);
        if (changeSetVersion < 1) {
            throw new IllegalArgumentException("change set version must be >= 1");
        }
    }
}
