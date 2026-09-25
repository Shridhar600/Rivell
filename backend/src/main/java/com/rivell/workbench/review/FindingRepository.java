package com.rivell.workbench.review;

import java.util.List;
import java.util.UUID;

/** Persistence port for assembled findings and their children (run-scoped). */
public interface FindingRepository {

    /** Replaces one run's findings atomically (re-assembly); other runs are untouched. */
    void replaceFindings(
            UUID organizationId, UUID reviewId, UUID runId, List<Finding> findings);

    /** Deletes a run's findings (derived state) when its checks are re-executed. */
    void deleteFindings(UUID organizationId, UUID runId);

    List<Finding> findFindings(UUID organizationId, UUID runId);

    int countFindings(UUID organizationId, UUID runId);
}
