package com.rivell.workbench.review.api;

import java.util.List;

/**
 * Bounded review list.
 *
 * <p>{@code truncated} means more rows exist beyond the limit and is retained for
 * compatibility with existing consumers. {@code nextCursor} is the opaque position to
 * resume from, and is non-null exactly when more rows exist; it is the only correct way
 * to walk this list, because a limit alone cannot say whether a page ended the data or
 * merely ended the budget.
 */
public record ReviewListResponse(
        List<ReviewListItemView> items, boolean truncated, String nextCursor) {
}
