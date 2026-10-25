package com.rivell.workbench.intelligence.provider;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Centralized model policy settings: the active provider id for semantic decisions, the waits
 * before each retry of a call whose provider is briefly unavailable (empty: no retry), the
 * absolute time one call may spend across attempts, and the in-flight allowance per provider.
 */
@ConfigurationProperties("rivell.intelligence")
public record IntelligenceProperties(String provider, List<Duration> retryBackoff,
        Duration retryMaxElapsed, Map<String, Integer> maxConcurrent) {

    public IntelligenceProperties {
        provider = provider == null || provider.isBlank() ? "jev" : provider;
        retryBackoff = retryBackoff == null
                ? List.of(Duration.ofSeconds(2), Duration.ofSeconds(8), Duration.ofSeconds(30))
                : List.copyOf(retryBackoff);
        retryMaxElapsed = retryMaxElapsed == null
                ? Duration.ofSeconds(90) : retryMaxElapsed;
        if (retryMaxElapsed.isZero() || retryMaxElapsed.isNegative()) {
            throw new IllegalArgumentException(
                    "rivell.intelligence.retry-max-elapsed must be positive");
        }
        var concurrency = new LinkedHashMap<String, Integer>();
        if (maxConcurrent != null) {
            maxConcurrent.forEach((id, count) -> {
                if (id != null && !id.isBlank() && count != null) {
                    if (count < 1) {
                        throw new IllegalArgumentException(
                                "rivell.intelligence.max-concurrent." + id + " must be >= 1");
                    }
                    concurrency.put(id, count);
                }
            });
        }
        concurrency.putIfAbsent("jev", 16);
        concurrency.putIfAbsent("openai-compatible", 8);
        concurrency.putIfAbsent("openai-decider", 8);
        concurrency.putIfAbsent("embeddings", 8);
        maxConcurrent = Map.copyOf(concurrency);
    }
}
