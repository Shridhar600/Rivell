package com.rivell.workbench.review.check;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AnalysisEnvironmentTest {

    @Test
    void mismatchReasonIsNullOnlyWhenFullyMatched() {
        assertThat(new AnalysisEnvironment(true, true, true, true).fullyMatches()).isTrue();
        assertThat(new AnalysisEnvironment(true, true, true, true).mismatchReason()).isNull();

        assertThat(new AnalysisEnvironment(false, true, true, true).mismatchReason())
                .isEqualTo("contract semantics changed after the snapshot was frozen");
        assertThat(new AnalysisEnvironment(true, false, true, true).mismatchReason())
                .isEqualTo("retrieval index version changed after the snapshot was frozen");
        assertThat(new AnalysisEnvironment(true, true, false, true).mismatchReason())
                .isEqualTo("embedding model identity changed after the snapshot was frozen");
        assertThat(new AnalysisEnvironment(true, true, true, false).mismatchReason())
                .isEqualTo("model policy bindings changed after the snapshot was frozen");
    }
}
