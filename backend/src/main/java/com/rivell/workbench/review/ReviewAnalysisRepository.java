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

    int maxRunNumber(UUID organizationId, UUID reviewId);

    void insertRun(ReviewAnalysisRun run);

    boolean markRunCompleted(UUID organizationId, UUID runId, Instant completedAt);

    boolean markRunFailed(UUID organizationId, UUID runId, String reason, Instant completedAt);

    Optional<ReviewCheckExecution> findExecution(
            UUID organizationId, UUID runId, UUID planCheckId);

    List<ReviewCheckExecution> findExecutions(UUID organizationId, UUID runId);

    boolean insertExecutionIfAbsent(ReviewCheckExecution execution);

    boolean updateExecution(
            UUID organizationId, UUID executionId, CheckExecutionStatus status,
            UUID evidenceTraceId, Map<String, Object> detail, int signalCount,
            Instant startedAt, Instant completedAt);

    void insertSignals(List<RawReviewSignal> signals);

    List<RawReviewSignal> findSignals(UUID organizationId, UUID runId);

    int countSignals(UUID organizationId, UUID runId);

    int countSignals(UUID organizationId, UUID runId, UUID planCheckId);
}
