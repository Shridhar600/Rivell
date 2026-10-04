package com.rivell.workbench.review;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read port for the organization-wide review list projection used by Home and the reviews
 * list. Implementations live in platform and must enforce organization scoping in SQL;
 * cross-organization access is indistinguishable from missing.
 *
 * <p>Only the first method is a page read. Every other method takes the ids of rows already
 * on the page, so a page costs a fixed number of statements no matter how many rows it
 * holds. Implementations read {@code limit + 1} rows from the page query so the caller can
 * detect that more rows exist and mint a cursor.
 */
public interface ReviewListQuery {

    /**
     * Newest-first page of reviews, narrowed to one contract when {@code contractId} is
     * present. {@code afterCreatedAt} and {@code afterId} resume after a previous page and
     * are both null for the first page.
     *
     * <p>The filename is that of the version the review is pinned to, read through the
     * review's own {@code contract_version_id}. It is never the contract's latest version.
     */
    List<ReviewListRow> findPage(
            UUID organizationId, UUID contractId,
            Instant afterCreatedAt, UUID afterId, int limit);

    /** Counted and latest generation per review, for the reviews on the page. */
    Map<UUID, ReviewListRunState> findRunStates(
            UUID organizationId, Collection<UUID> reviewIds);

    /** Declared plan-check count per review. */
    Map<UUID, Integer> countPlannedChecks(UUID organizationId, Collection<UUID> reviewIds);

    /** Execution status totals per counted run. */
    Map<UUID, ReviewExecutionCounts> countExecutions(
            UUID organizationId, Collection<UUID> runIds);

    /** Raw signal totals per counted run. */
    Map<UUID, Integer> countSignals(UUID organizationId, Collection<UUID> runIds);

    /** Finding totals per counted run. */
    Map<UUID, Integer> countFindings(UUID organizationId, Collection<UUID> runIds);

    /**
     * Finalization state per review, absent for a review that has not been finalized.
     *
     * <p>The approved-change count is the frozen change set's own size, aggregated in SQL
     * so a finalized review with nothing approved still reports an honest zero instead of
     * disappearing from the result.
     */
    Map<UUID, ReviewListFinalization> findFinalizations(
            UUID organizationId, Collection<UUID> reviewIds);

    /**
     * Redline state per review, absent for a review no redline was ever requested for.
     *
     * <p>At most one execution can exist per review: a change set is unique per review and
     * an execution is unique per change set. A verified-change count is reported only for
     * an execution that completed and produced a redline, so a failed run's internally
     * verified changes are never surfaced as a verified total.
     */
    Map<UUID, ReviewListRedline> findRedlines(
            UUID organizationId, Collection<UUID> reviewIds);
}
