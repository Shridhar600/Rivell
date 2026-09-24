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
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
