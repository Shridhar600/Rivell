package com.rivell.workbench.review;

import java.time.Instant;
import java.util.UUID;

/**
 * One page row of the organization-wide review list: identity, the filename of the
 * contract version this review is pinned to, lifecycle, and the raw failure reason for
 * public mapping.
 *
 * <p>Internal snapshot identities, run identities and model bindings are deliberately
 * absent: a list row is not an addressable resource.
 */
public record ReviewListRow(
        UUID reviewId,
        UUID contractId,
        int contractVersionNumber,
        String contractFilename,
        String status,
        String failureReason,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt) {
}