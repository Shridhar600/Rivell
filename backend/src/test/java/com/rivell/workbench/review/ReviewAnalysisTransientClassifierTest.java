package com.rivell.workbench.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLTransientException;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessResourceException;

/** R4/R2: which failures may be retried without killing the review. */
class ReviewAnalysisTransientClassifierTest {

    @Test
    void everyListedTransientTypeIsRecognised() {
        assertThat(ReviewAnalysisService.isTransientFailure(
                new TransientDataAccessResourceException("pool exhausted"))).isTrue();
        assertThat(ReviewAnalysisService.isTransientFailure(
                new RecoverableDataAccessException("recoverable"))).isTrue();
        assertThat(ReviewAnalysisService.isTransientFailure(
                new DataAccessResourceFailureException("connection refused"))).isTrue();
        assertThat(ReviewAnalysisService.isTransientFailure(
                new QueryTimeoutException("statement timeout"))).isTrue();
        assertThat(ReviewAnalysisService.isTransientFailure(
                new java.sql.SQLTransientConnectionException("connection reset"))).isTrue();
        assertThat(ReviewAnalysisService.isTransientFailure(
                new ReviewAnalysisService.LeaseLostException(UUID.randomUUID()))).isTrue();
    }

    @Test
    void aTransientCauseAnywhereInTheChainIsEnough() {
        var failure = new IllegalStateException("terminal write failed",
                new RuntimeException("while updating review_check_execution",
                        new SQLTransientException("deadlock detected")));

        assertThat(ReviewAnalysisService.isTransientFailure(failure)).isTrue();
    }

    @Test
    void anOrdinaryRuntimeFailureIsNotTransient() {
        assertThat(ReviewAnalysisService.isTransientFailure(
                new IllegalStateException("snapshot missing"))).isFalse();
        assertThat(ReviewAnalysisService.isTransientFailure(
                new IllegalArgumentException("bad configuration"))).isFalse();
        assertThat(ReviewAnalysisService.isTransientFailure(
                new RuntimeException(new IllegalStateException("nested but terminal")))).isFalse();
    }

    @Test
    void theRecordedReasonIsTheCodeOwnedTypeNeverTheDriverMessage() {
        var failure = new IllegalStateException("wrapper",
                new TransientDataAccessResourceException(
                        "connection to db-host:5432 refused for user rivell"));

        assertThat(ReviewAnalysisService.transientReason(failure))
                .isEqualTo("TransientDataAccessResourceException")
                .doesNotContain("db-host")
                .doesNotContain("rivell");
    }

    @Test
    void aSelfReferentialCauseChainTerminates() {
        var first = new RuntimeException("first");
        var second = new RuntimeException("second");
        first.initCause(second);
        second.initCause(first);

        assertThat(ReviewAnalysisService.isTransientFailure(first)).isFalse();
    }
}
