package com.rivell.workbench.intelligence.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class IntelligencePropertiesTest {

    @Test
    void retryAndConcurrencyDefaultsKeepTheSingleReviewPathUnthrottled() {
        var properties = new IntelligenceProperties(null, null, null, null);

        assertThat(properties.retryBackoff())
                .containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(8),
                        Duration.ofSeconds(30));
        assertThat(properties.retryMaxElapsed()).isEqualTo(Duration.ofSeconds(90));
        assertThat(properties.maxConcurrent())
                .containsEntry("jev", 16)
                .containsEntry("openai-compatible", 8)
                .containsEntry("embeddings", 8);
    }

    @Test
    void aNonPositiveElapsedBudgetOrPermitCountIsRefusedAtStartup() {
        assertThatThrownBy(() -> new IntelligenceProperties(null, List.of(), Duration.ZERO, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retry-max-elapsed");
        assertThatThrownBy(() -> new IntelligenceProperties(null, null, null,
                Map.of("jev", 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-concurrent");
    }
}
