package com.rivell.workbench.taxonomy;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Topic classification thresholds. Defaults come from the 2026-10-10 dev-corpus probe:
 * at 0.5 only genuinely off-taxonomy sections had no topic; at 0.8, 1 in 6 did.
 */
@ConfigurationProperties("rivell.taxonomy")
public record TaxonomyProperties(
        double topicThreshold,
        double primaryThreshold,
        double primaryMargin,
        int textLimit,
        int maxPrimaryCandidates) {

    public TaxonomyProperties {
        topicThreshold = topicThreshold <= 0.0 ? 0.5 : topicThreshold;
        primaryThreshold = primaryThreshold <= 0.0 ? 0.8 : primaryThreshold;
        primaryMargin = primaryMargin <= 0.0 ? 0.10 : primaryMargin;
        textLimit = textLimit <= 0 ? 4000 : textLimit;
        maxPrimaryCandidates = maxPrimaryCandidates <= 1 ? 8 : maxPrimaryCandidates;
    }
}
