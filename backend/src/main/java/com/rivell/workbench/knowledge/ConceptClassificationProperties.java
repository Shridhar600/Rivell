package com.rivell.workbench.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Task-specific policy for legal-concept classification. Thresholds are decision- and
 * risk-specific by design; there is no global confidence threshold.
 */
@ConfigurationProperties("rivell.intelligence.classification")
public record ConceptClassificationProperties(double conceptThreshold) {

    public ConceptClassificationProperties {
        if (!Double.isFinite(conceptThreshold) || conceptThreshold <= 0.0 || conceptThreshold > 1.0) {
            throw new IllegalArgumentException("Concept threshold must be a value in (0,1]");
        }
    }
}
