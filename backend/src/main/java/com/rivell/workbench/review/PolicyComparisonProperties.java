package com.rivell.workbench.review;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Policy-comparison bounds (design §6a): at most {@code maxPositions} published
 * positions are judged per clause, and a position flags when the summed probability of
 * the four issue-bearing options reaches {@code flagThreshold}.
 */
@ConfigurationProperties("rivell.review.policy-comparison")
public record PolicyComparisonProperties(int maxPositions, double flagThreshold) {

    public PolicyComparisonProperties {
        maxPositions = maxPositions <= 0 ? 8 : maxPositions;
        flagThreshold = flagThreshold <= 0.0 || flagThreshold > 1.0 ? 0.6 : flagThreshold;
    }
}
