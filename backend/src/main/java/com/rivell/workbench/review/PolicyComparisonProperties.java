package com.rivell.workbench.review;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Policy-comparison bounds (design §6a): at most {@code maxPositions} published
 * positions are judged per clause, and a position flags when the summed probability of
 * the four issue-bearing options reaches {@code flagThreshold}. A clause no position
 * addresses becomes a finding when the triage probability reaches
 * {@code uncoveredThreshold} (design §6c). {@code issueSpotting} (opt-in, step-6 hybrid) adds
 * one whole-contract LLM read per run: a clause it names that no check flagged becomes a
 * needs-review finding. {@code orderProbe} (measurement only) re-asks each comparison; see
 * {@link OrderProbe}.
 */
@ConfigurationProperties("rivell.review.policy-comparison")
public record PolicyComparisonProperties(
        int maxPositions, double flagThreshold, double uncoveredThreshold, boolean issueSpotting,
        OrderProbe orderProbe) {

    /**
     * Measurement only (engine v2 P0): ask every comparison a second time, with the options
     * reversed or exactly as asked (the noise floor), and keep that answer in the check detail.
     * It never changes a finding.
     */
    public enum OrderProbe { OFF, REVERSED, REPEAT }

    public PolicyComparisonProperties {
        maxPositions = maxPositions <= 0 ? 8 : maxPositions;
        flagThreshold = flagThreshold <= 0.0 || flagThreshold > 1.0 ? 0.6 : flagThreshold;
        uncoveredThreshold = uncoveredThreshold <= 0.0 || uncoveredThreshold > 1.0
                ? 0.5 : uncoveredThreshold;
        orderProbe = orderProbe == null ? OrderProbe.OFF : orderProbe;
    }
}
