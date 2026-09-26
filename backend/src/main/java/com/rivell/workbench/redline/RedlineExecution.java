package com.rivell.workbench.redline;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One canonical deterministic execution of a frozen ApprovedChangeSet against the exact
 * frozen source DOCX. Immutable; execution state never mutates M7/M8 rows.
 */
public record RedlineExecution(
        UUID id,
        UUID organizationId,
        UUID reviewId,
        UUID changeSetId,
        UUID contractId,
        UUID contractVersionId,
        int contractVersionNumber,
        int changeSetVersion,
        RedlineExecutionStatus status,
        RedlineOutcome outcome,
        RedlineFailureCode failureCode,
        String failureDetail,
        String rendererVersion,
        String placementStrategyVersion,
        String sourceSha256,
        int changeCount,
        UUID requestedBy,
        Instant startedAt,
        Instant completedAt,
        List<RedlineExecutionChange> changes,
        RedlineArtifact artifact) {

    public RedlineExecution {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(reviewId, "reviewId");
        Objects.requireNonNull(changeSetId, "changeSetId");
        Objects.requireNonNull(contractId, "contractId");
        Objects.requireNonNull(contractVersionId, "contractVersionId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(rendererVersion, "rendererVersion");
        Objects.requireNonNull(placementStrategyVersion, "placementStrategyVersion");
        Objects.requireNonNull(sourceSha256, "sourceSha256");
        Objects.requireNonNull(requestedBy, "requestedBy");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(completedAt, "completedAt");
        changes = changes == null ? List.of() : List.copyOf(changes);
        if (contractVersionNumber < 1 || changeSetVersion < 1) {
            throw new IllegalArgumentException("version numbers must be >= 1");
        }
        if (changeCount < 0) {
            throw new IllegalArgumentException("change count must be >= 0");
        }
        if ((status == RedlineExecutionStatus.COMPLETED) != (outcome != null)) {
            throw new IllegalArgumentException("only completed executions carry an outcome");
        }
        if ((status == RedlineExecutionStatus.FAILED) != (failureCode != null)) {
            throw new IllegalArgumentException("failed executions carry a failure code");
        }
        if (outcome == RedlineOutcome.NO_CHANGES && changeCount != 0) {
            throw new IllegalArgumentException("a no-change execution carries no changes");
        }
        if (artifact != null && !id.equals(artifact.executionId())) {
            throw new IllegalArgumentException("artifact belongs to another execution");
        }
        for (var change : changes) {
            if (!id.equals(change.executionId())) {
                throw new IllegalArgumentException("change belongs to another execution");
            }
        }
    }

    public boolean downloadable() {
        return status == RedlineExecutionStatus.COMPLETED && artifact != null;
    }
}
