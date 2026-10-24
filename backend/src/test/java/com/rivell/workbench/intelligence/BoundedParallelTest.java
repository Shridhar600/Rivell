package com.rivell.workbench.intelligence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class BoundedParallelTest {

    @Test
    void workersSeeTheSubmittingThreadsMdc() {
        MDC.put("reviewId", "review-1");
        try {
            var outcomes = BoundedParallel.map(List.of(1, 2), 2,
                    item -> MDC.get("reviewId"));

            assertThat(outcomes).allSatisfy(outcome -> {
                assertThat(outcome.failure()).isNull();
                assertThat(outcome.value()).isEqualTo("review-1");
            });
        } finally {
            MDC.clear();
        }
    }
}
