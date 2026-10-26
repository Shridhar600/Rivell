package com.rivell.workbench.intelligence.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class EmbeddingPropertiesDimensionGuardTest {

    private static EmbeddingProperties properties(Integer dimensions) {
        return new EmbeddingProperties("openai-compatible", null, "key",
                "embedding-model", dimensions, 64, null, null);
    }

    @Test
    void configuredDimensionsWithinPgvectorCapabilityAreAccepted() {
        assertThat(properties(768).dimensions()).isEqualTo(768);
        assertThat(properties(1536).dimensions()).isEqualTo(1536);
        assertThat(properties(EmbeddingProperties.MAX_INDEXABLE_DIMENSIONS).dimensions())
                .isEqualTo(2000);
        assertThat(properties(null).dimensions()).isNull();
    }

    @Test
    void theApiKeyIsRedactedFromTheRecordToString() {
        var properties = new EmbeddingProperties("openai-compatible", null,
                "embedding-live-secret", "embedding-model", 768, 64, null, null);

        assertThat(properties.toString())
                .doesNotContain("embedding-live-secret")
                .contains("apiKey=<redacted>");
        assertThat(new EmbeddingProperties("openai-compatible", null, " ",
                "embedding-model", 768, 64, null, null).toString())
                .contains("apiKey=<absent>");
    }

    @Test
    void dimensionsBeyondPgvectorHnswCapabilityFailFast() {
        assertThatThrownBy(() -> properties(2001))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2001")
                .hasMessageContaining("cannot proceed");
        assertThatThrownBy(() -> properties(4000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HNSW index capability");
    }

    @Test
    void nonPositiveDimensionsFailFast() {
        assertThatThrownBy(() -> properties(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> properties(-768))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
