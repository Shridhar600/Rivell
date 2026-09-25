package com.rivell.workbench.review.api;

import java.util.List;

/** Bounded finding list; {@code truncated} means more rows exist beyond the limit. */
public record FindingListResponse(List<FindingSummaryView> items, boolean truncated) {
}
