package com.rivell.workbench.intelligence.provider;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Centralized model policy settings: the active provider id for semantic decisions, and the
 * waits before each retry of a call whose provider is briefly unavailable (empty: no retry).
 */
@ConfigurationProperties("rivell.intelligence")
public record IntelligenceProperties(String provider, List<Duration> retryBackoff) {

    public IntelligenceProperties {
        provider = provider == null || provider.isBlank() ? "jev" : provider;
        retryBackoff = retryBackoff == null
                ? List.of(Duration.ofSeconds(2), Duration.ofSeconds(8), Duration.ofSeconds(30))
                : List.copyOf(retryBackoff);
    }
}
