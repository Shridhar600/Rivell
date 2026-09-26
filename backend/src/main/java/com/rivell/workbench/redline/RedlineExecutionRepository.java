package com.rivell.workbench.redline;

import java.util.Optional;
import java.util.UUID;

/** Persistence port for redline executions (organization-scoped). */
public interface RedlineExecutionRepository {

    Optional<RedlineExecution> findByReview(UUID organizationId, UUID reviewId);

    Optional<RedlineExecution> findByChangeSet(UUID organizationId, UUID changeSetId);

    /**
     * Inserts the execution with its change report and artifact atomically. Returns
     * false when a canonical execution already exists for the change set (concurrent
     * trigger); the caller then reads the winner.
     */
    boolean insert(RedlineExecution execution);
}
