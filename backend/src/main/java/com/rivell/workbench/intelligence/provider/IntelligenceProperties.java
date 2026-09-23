package com.rivell.workbench.intelligence.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Centralized model policy settings: the active provider id for semantic decisions.
 */
@ConfigurationProperties("rivell.intelligence")
public record IntelligenceProperties(String provider) {

    public IntelligenceProperties {
        provider = provider == null || provider.isBlank() ? "jev" : provider;
    }
}
