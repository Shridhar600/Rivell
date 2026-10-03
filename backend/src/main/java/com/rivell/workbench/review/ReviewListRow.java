package com.rivell.workbench.review;

import java.time.Instant;
import java.util.UUID;

/**
 * One page row of the organization-wide review list: identity, the filename and
 * ingestion time of the contract version this review is pinned to, the frozen
 * human-declared review context, lifecycle, and the raw failure reason for public
 * mapping.
 *
 * <p>{@code uploadedAt} is the pinned version's own ingestion time. It is never the
 * contract's latest version and never the review's creation time, because a row that
 * reported review time under an upload label would misdate the document.
 *
 * <p>Internal snapshot identities, run identities and model bindings are deliberately
 * absent: a list row is not an addressable resource.
 */
public record ReviewListRow(
        UUID reviewId,
        UUID contractId,
        int contractVersionNumber,
        String contractFilename,
        Instant uploadedAt,
        ReviewContextSummary context,
        String status,
        String failureReason,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt) {
}
