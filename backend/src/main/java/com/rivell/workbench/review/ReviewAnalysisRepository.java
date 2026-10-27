package com.rivell.workbench.review;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/** Persistence port for analysis runs, check executions, and raw signals. */
public interface ReviewAnalysisRepository {

    Optional<ReviewAnalysisRun> findRunningRun(UUID organizationId, UUID reviewId);

    Optional<ReviewAnalysisRun> findLatestRun(UUID organizationId, UUID reviewId);

    /** Active generation: the latest COMPLETED run (spec §2.2). */
    Optional<ReviewAnalysisRun> findLatestCompletedRun(UUID organizationId, UUID reviewId);

    int maxRunNumber(UUID organizationId, UUID reviewId);

    boolean tryInsertRun(ReviewAnalysisRun run);

    boolean markRunCompleted(UUID organizationId, UUID runId, Instant completedAt);

    /** Stores the run's frozen playbook coverage; written in the completion transaction. */
    boolean savePlaybookCoverage(
            UUID organizationId, UUID runId, PlaybookCoverage playbookCoverage);

    boolean markRunFailed(UUID organizationId, UUID runId, String reason, Instant completedAt);

    /**
     * Counts one transient failure against a RUNNING run, records its code-owned reason, and
     * releases the lease this worker held on the check (if any), so a JobRunr retry need not wait
     * for the 10-minute lease expiry. Empty when the run is no longer RUNNING; another worker owns
     * the terminal state. The caller runs this in its own transaction so the counter does not roll
     * back with the failed check write.
     */
    OptionalInt recordTransientFailure(
            UUID organizationId, UUID runId, String reason,
            UUID executionId, UUID leaseId);

    Optional<ReviewCheckExecution> findExecution(
            UUID organizationId, UUID runId, UUID planCheckId);

    List<ReviewCheckExecution> findExecutions(UUID organizationId, UUID runId);

    boolean insertExecutionIfAbsent(ReviewCheckExecution execution);

    /**
     * Claims a PENDING or stale RUNNING execution by minting a lease token. Empty when
     * another worker holds a fresh lease; the token fences the terminal write.
     */
    Optional<UUID> claimExecution(UUID organizationId, UUID executionId);

    /**
     * Terminal write fenced by the lease token; false when the lease was lost (the caller
     * must abort its transaction so a stale worker cannot replace the winner's signals).
     */
    boolean completeExecution(
            UUID organizationId, UUID executionId, UUID leaseId,
            CheckExecutionStatus status, UUID evidenceTraceId, Map<String, Object> detail,
            int signalCount, Instant completedAt);

    void insertSignals(List<RawReviewSignal> signals);

    /** Deletes one check's signals and the finding links that cite them. */
    int deleteSignals(UUID organizationId, UUID runId, UUID planCheckId);

    List<RawReviewSignal> findSignals(UUID organizationId, UUID runId);

    int countSignals(UUID organizationId, UUID runId);

    int countSignals(UUID organizationId, UUID runId, UUID planCheckId);

    /** Replaces the frozen evidence items cited by one check execution (G-M7-1). */
    void replaceEvidenceItems(
            UUID organizationId, UUID reviewId, UUID runId, UUID planCheckId,
            List<com.rivell.workbench.retrieval.EvidenceItem> items);

    /**
     * The exact evidence items persisted for the given run, filtered by organization and
     * evidence ids. Missing ids are absent from the result (the caller fails loudly).
     */
    List<com.rivell.workbench.retrieval.EvidenceItem> findEvidenceItems(
            UUID organizationId, UUID runId, java.util.Collection<UUID> evidenceItemIds);
}
