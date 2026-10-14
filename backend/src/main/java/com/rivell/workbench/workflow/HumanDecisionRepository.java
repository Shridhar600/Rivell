package com.rivell.workbench.workflow;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for append-only human decision revisions (organization-scoped). */
public interface HumanDecisionRepository {

    /** The current effective decision (highest revision) for one finding. */
    Optional<HumanDecision> findCurrent(UUID organizationId, UUID reviewId, UUID findingId);

    /** Current effective decisions for every finding of one analysis run. */
    List<HumanDecision> findCurrentForRun(UUID organizationId, UUID reviewId, UUID runId);

    /**
     * Counts the current effective decision per action for one run, without materialising them.
     *
     * <p>Same {@code distinct on (finding_id)} semantics as {@link #findCurrentForRun}, so the
     * totals match a list-based count exactly. Diagnostics needs four numbers and was reading a
     * bounded list to derive them, which both loaded rows and silently under-reported past the bound.
     */
    Map<DecisionAction, Long> countCurrentByAction(
            UUID organizationId, UUID reviewId, UUID runId);

    /**
     * Bounded current-decision list for one run; implementations read
     * {@code limit + 1} rows so callers can detect truncation.
     */
    List<HumanDecision> findCurrentForRun(
            UUID organizationId, UUID reviewId, UUID runId, int limit);

    /** Revision history newest-first; implementations read {@code limit + 1} rows. */
    List<HumanDecision> findHistory(
            UUID organizationId, UUID reviewId, UUID findingId, int limit);

    Optional<HumanDecision> findByClientRequestId(
            UUID organizationId, UUID findingId, UUID clientRequestId);

    /** Appends one revision; false when (finding_id, revision) already exists. */
    boolean insert(HumanDecision decision);
}
