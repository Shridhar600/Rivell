package com.rivell.workbench.review;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One analysis generation of a review (R2: active state; history append-only). */
public record ReviewAnalysisRun(
        UUID id,
        UUID organizationId,
        UUID reviewId,
        int runNumber,
        RunStatus status,
        RunTrigger trigger,
        String pipelineVersion,
        Instant startedAt,
        Instant completedAt,
        String failureReason,
        int transientFailures,
        String lastTransientReason) {

    public ReviewAnalysisRun {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(reviewId, "reviewId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(pipelineVersion, "pipelineVersion");
        Objects.requireNonNull(startedAt, "startedAt");
        if (runNumber < 1) {
            throw new IllegalArgumentException("runNumber must be >= 1");
        }
        if ((status == RunStatus.FAILED) != (failureReason != null)) {
            throw new IllegalArgumentException("failureReason is required iff FAILED");
        }
        if ((status == RunStatus.RUNNING) != (completedAt == null)) {
            throw new IllegalArgumentException(
                    "completedAt is the terminal timestamp; unset iff RUNNING");
        }
        if (transientFailures < 0) {
            throw new IllegalArgumentException("transientFailures must be >= 0");
        }
    }

    public static ReviewAnalysisRun running(
            UUID id, UUID organizationId, UUID reviewId, int runNumber, RunTrigger trigger,
            String pipelineVersion, Instant startedAt) {
        return new ReviewAnalysisRun(id, organizationId, reviewId, runNumber, RunStatus.RUNNING,
                trigger, pipelineVersion, startedAt, null, null, 0, null);
    }
}
