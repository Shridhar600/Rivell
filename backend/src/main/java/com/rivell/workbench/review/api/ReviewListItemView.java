package com.rivell.workbench.review.api;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the review list.
 *
 * <p>Identity and the pinned contract version come from the review. The lifecycle,
 * generation and progress numbers are the same truth {@link ReviewSummaryView} reports,
 * aggregated for the whole page at once, so a rendered row never needs a follow-up
 * request to know what state its review is in.
 *
 * <p>The pinned contract version's filename is included because a row without it cannot
 * identify its own work. Internal snapshot identities, run identifiers and model bindings
 * are deliberately absent: a list row is not an addressable resource.
 */
public record ReviewListItemView(
        UUID id,
        UUID contractId,
        int contractVersionNumber,
        String contractFilename,
        String status,
        ReviewSummaryView.FailureView failure,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        Integer activeRunNumber,
        String activeRunStatus,
        String latestRunStatus,
        int plannedChecks,
        int completedChecks,
        int degradedChecks,
        int failedChecks,
        int signals,
        int findings) {
}
