package com.rivell.workbench.review;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Review aggregate root: machine analysis of one contract version under a frozen
 * snapshot. Creation writes review + snapshot + plan atomically, so no half-created
 * PREPARING state is ever observable.
 */
public record Review(
        UUID id,
        UUID organizationId,
        UUID contractId,
        UUID contractVersionId,
        int contractVersionNumber,
        ReviewStatus status,
        String failureReason,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt,
        Instant completedAt,
        UUID clientRequestId) {

    public Review {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(contractId, "contractId");
        Objects.requireNonNull(contractVersionId, "contractVersionId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (contractVersionNumber < 1) {
            throw new IllegalArgumentException("contractVersionNumber must be >= 1");
        }
        if ((status == ReviewStatus.COMPLETED || status == ReviewStatus.FAILED)
                != (completedAt != null)) {
            throw new IllegalArgumentException(
                    "completedAt is set iff the review is terminal");
        }
        if ((status == ReviewStatus.FAILED) != (failureReason != null)) {
            throw new IllegalArgumentException("failureReason is required iff FAILED");
        }
    }

    public static Review analysisQueued(
            UUID id, UUID organizationId, UUID contractId, UUID contractVersionId,
            int contractVersionNumber, UUID createdBy, Instant now) {
        return analysisQueued(id, organizationId, contractId, contractVersionId,
                contractVersionNumber, createdBy, now, null);
    }

    public static Review analysisQueued(
            UUID id, UUID organizationId, UUID contractId, UUID contractVersionId,
            int contractVersionNumber, UUID createdBy, Instant now, UUID clientRequestId) {
        return new Review(id, organizationId, contractId, contractVersionId,
                contractVersionNumber, ReviewStatus.ANALYSIS_QUEUED, null, createdBy,
                now, now, null, null, clientRequestId);
    }
}
