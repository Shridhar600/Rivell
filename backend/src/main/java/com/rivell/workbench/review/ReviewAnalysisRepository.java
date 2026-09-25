package com.rivell.workbench.review;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    boolean markRunFailed(UUID organizationId, UUID runId, String reason, Instant completedAt);

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

    int deleteSignals(UUID organizationId, UUID runId, UUID planCheckId);

    List<RawReviewSignal> findSignals(UUID organizationId, UUID runId);

    int countSignals(UUID organizationId, UUID runId);

    int countSignals(UUID organizationId, UUID runId, UUID planCheckId);
}
