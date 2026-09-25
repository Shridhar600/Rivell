package com.rivell.workbench.review.api;

import java.util.List;

/** Bounded review list; {@code truncated} means more rows exist beyond the limit. */
public record ReviewListResponse(List<ReviewListItemView> items, boolean truncated) {
}
