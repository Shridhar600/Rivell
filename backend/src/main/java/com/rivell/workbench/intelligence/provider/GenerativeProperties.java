package com.rivell.workbench.intelligence.provider;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpenAI-compatible generative transport configuration. Serves OpenAI direct,
 * OpenRouter, and Vercel AI Gateway by configuration alone; the key is
 * env-injected and never logged. Separated from Jev deliberately (different
 * capability boundary).
 */
@ConfigurationProperties("rivell.intelligence.generative")
public record GenerativeProperties(
        String provider,
        String baseUrl,
        String apiKey,
        String model,
        Duration connectTimeout,
        Duration readTimeout) {

    public GenerativeProperties {
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
            readTimeout = Duration.ofSeconds(45);
        }
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
