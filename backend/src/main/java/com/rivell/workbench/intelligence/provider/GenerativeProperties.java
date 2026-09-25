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
        String responseMode,
        Duration connectTimeout,
        Duration readTimeout) {

    public static final String RESPONSE_MODE_STRICT_SCHEMA = "strict-schema";
    public static final String RESPONSE_MODE_JSON_OBJECT = "json-object";

    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public GenerativeProperties {
        if (provider == null || provider.isBlank()) {
            provider = "openai-compatible";
        }
        if (responseMode == null || responseMode.isBlank()) {
            responseMode = RESPONSE_MODE_STRICT_SCHEMA;
        }
        if (!RESPONSE_MODE_STRICT_SCHEMA.equals(responseMode)
                && !RESPONSE_MODE_JSON_OBJECT.equals(responseMode)) {
            throw new IllegalArgumentException(
                    "responseMode must be strict-schema or json-object");
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

    public GenerativeProperties(
            String provider, String baseUrl, String apiKey, String model,
            Duration connectTimeout, Duration readTimeout) {
        this(provider, baseUrl, apiKey, model, null, connectTimeout, readTimeout);
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
