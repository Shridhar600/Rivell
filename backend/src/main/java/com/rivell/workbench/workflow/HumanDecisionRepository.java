package com.rivell.workbench.workflow;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for append-only human decision revisions (organization-scoped). */
public interface HumanDecisionRepository {

    /** The current effective decision (highest revision) for one finding. */
    Optional<HumanDecision> findCurrent(UUID organizationId, UUID reviewId, UUID findingId);

    /** Current effective decisions for every finding of one analysis run. */
    List<HumanDecision> findCurrentForRun(UUID organizationId, UUID reviewId, UUID runId);

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
