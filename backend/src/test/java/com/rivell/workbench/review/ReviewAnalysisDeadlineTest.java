package com.rivell.workbench.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * R4/R2: the budget belongs to the attempt, not to the run's age, so a resumed run is not degraded
 * wholesale. The run still has a hard total so an endlessly retried run cannot spend forever.
 */
class ReviewAnalysisDeadlineTest {

    private static final Duration BUDGET = Duration.ofMinutes(30);

    @Test
    void aFreshAttemptGetsItsOwnBudget() {
        assertThat(ReviewAnalysisService.analysisDeadline(
                Instant.parse("2026-10-24T09:50:00Z"),
                Instant.parse("2026-10-24T10:00:00Z"), BUDGET, 3))
                .isEqualTo(Instant.parse("2026-10-24T10:30:00Z"));
    }

    @Test
    void aResumedRunOlderThanTheBudgetStillGetsTheCurrentAttemptsBudget() {
        assertThat(ReviewAnalysisService.analysisDeadline(
                Instant.parse("2026-10-24T09:00:00Z"),
                Instant.parse("2026-10-24T10:00:00Z"), BUDGET, 3))
                .isEqualTo(Instant.parse("2026-10-24T10:30:00Z"));
    }

    @Test
    void theWholeRunIsBoundedByTheAttemptsTheTransientCounterAllows() {
        assertThat(ReviewAnalysisService.analysisDeadline(
                Instant.parse("2026-10-24T07:00:00Z"),
                Instant.parse("2026-10-24T10:00:00Z"), BUDGET, 3))
                .isEqualTo(Instant.parse("2026-10-24T09:00:00Z"));
    }

    @Test
    void noRetriesMeansExactlyOneAttemptBudget() {
        assertThat(ReviewAnalysisService.analysisDeadline(
                Instant.parse("2026-10-24T08:00:00Z"),
                Instant.parse("2026-10-24T10:00:00Z"), BUDGET, 0))
                .isEqualTo(Instant.parse("2026-10-24T08:30:00Z"));
    }
}
