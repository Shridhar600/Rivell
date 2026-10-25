package com.rivell.workbench.intelligence.provider;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpenAI-compatible embedding transport configuration (Architecture Decision #9).
 * Serves OpenAI direct and OpenRouter by configuration alone; the key is
 * env-injected and never logged. Provider/model identity changes must be explicit:
 * retrieval only compares vectors recorded under one model identity.
 */
@ConfigurationProperties("rivell.intelligence.embeddings")
public record EmbeddingProperties(
        String provider,
        String baseUrl,
        String apiKey,
        String model,
        Integer dimensions,
        int maxInputsPerBatch,
        Duration connectTimeout,
        Duration readTimeout) {

    /**
     * pgvector regular-vector/HNSW index capability (pgvector 0.8.6). Storage/index
     * semantics, not a model constant: a configured dimension count beyond this can
     * never be indexed and must fail before indexing happens.
     */
    public static final int MAX_INDEXABLE_DIMENSIONS = 2000;

    public EmbeddingProperties {
        if (provider == null || provider.isBlank()) {
            provider = "openai-compatible";
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.openai.com/v1";
        }
        if (connectTimeout == null) {
            connectTimeout = Duration.ofSeconds(5);
        }
        if (readTimeout == null) {
            readTimeout = Duration.ofSeconds(30);
        }
        if (maxInputsPerBatch < 1) {
            maxInputsPerBatch = 64;
        }
        if (maxInputsPerBatch > 2048) {
            throw new IllegalArgumentException(
                    "rivell.intelligence.embeddings.max-inputs-per-batch "
                            + maxInputsPerBatch + " exceeds the provider limit of 2048");
        }
        if (dimensions != null) {
            if (dimensions < 1) {
                throw new IllegalArgumentException("Embedding dimensions must be positive");
            }
            if (dimensions > MAX_INDEXABLE_DIMENSIONS) {
                throw new IllegalArgumentException(
                        "Configured embedding dimensions " + dimensions
                                + " exceed the pgvector regular-vector/HNSW index capability ("
                                + MAX_INDEXABLE_DIMENSIONS + "); indexing cannot proceed");
            }
        }
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
