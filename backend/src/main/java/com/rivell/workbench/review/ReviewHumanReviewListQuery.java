package com.rivell.workbench.review;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Batched human-review state for the reviews on one list page.
 *
 * <p>Implementations must resolve the counts and the readiness assessment with the same
 * rules the single-review human-review read uses, so a list row cannot report a review as
 * ready to finalize when its own detail endpoint reports otherwise. They must also be
 * batched: one call covers the whole page regardless of its size.
 */
public interface ReviewHumanReviewListQuery {

    /** Human-review state per requested review; absent when a review could not be read. */
    Map<UUID, ReviewHumanReviewSummary> findProgress(
            UUID organizationId, Collection<ReviewHumanReviewListInput> inputs);
}
