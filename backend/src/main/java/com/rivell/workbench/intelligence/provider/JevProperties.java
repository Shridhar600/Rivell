package com.rivell.workbench.intelligence.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * TypeSafe/Jev adapter configuration. Values come from configuration; the API key is
 * injected from the environment and never logged or exposed.
 */
@ConfigurationProperties("rivell.intelligence.jev")
public record JevProperties(
        String baseUrl,
        String apiKey,
        String model,
        java.time.Duration connectTimeout,
        java.time.Duration readTimeout) {

    public JevProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("Jev base URL must not be blank");
        }
        model = model == null || model.isBlank() ? "jev-latest" : model;
        connectTimeout = connectTimeout == null ? java.time.Duration.ofSeconds(2) : connectTimeout;
        readTimeout = readTimeout == null ? java.time.Duration.ofSeconds(10) : readTimeout;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
