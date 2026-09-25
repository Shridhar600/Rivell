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
        Instant completedAt) {

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
    }

    public static Review analysisQueued(
            UUID id, UUID organizationId, UUID contractId, UUID contractVersionId,
            int contractVersionNumber, UUID createdBy, Instant now) {
        return new Review(id, organizationId, contractId, contractVersionId,
                contractVersionNumber, ReviewStatus.ANALYSIS_QUEUED, null, createdBy,
                now, now, null, null);
    }
}
