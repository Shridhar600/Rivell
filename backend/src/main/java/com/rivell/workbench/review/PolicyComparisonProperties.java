package com.rivell.workbench.review;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Policy-comparison bounds (design §6a): at most {@code maxPositions} published
 * positions are judged per clause, and a position flags when the summed probability of
 * the four issue-bearing options reaches {@code flagThreshold}. A clause no position
 * addresses becomes a finding when the triage probability reaches
 * {@code uncoveredThreshold} (design §6c). {@code issueSpotting} (opt-in, step-6 hybrid) adds
 * one whole-contract LLM read per run in v1; engine v2 reads every run (Task 4) and turns its
 * picks into advisory items, of which {@code advisorySpots} keeps {@code ALL} (default) or only
 * the read's {@code HIGH} picks. {@code orderProbe} (measurement only) re-asks each comparison;
 * see {@link OrderProbe}. {@code searchLimit} (engine v2 SEARCH route) is how many positions a
 * text search over the playbook hands to the comparison for a clause no paired position
 * addresses.
 */
@ConfigurationProperties("rivell.review.policy-comparison")
public record PolicyComparisonProperties(
        int maxPositions, double flagThreshold, double uncoveredThreshold, boolean issueSpotting,
        OrderProbe orderProbe, int searchLimit, AdvisorySpots advisorySpots) {

    /**
     * Measurement only (engine v2 P0): ask every comparison a second time, with the options
     * reversed or exactly as asked (the noise floor), and keep that answer in the check detail.
     * It never changes a finding.
     */
    public enum OrderProbe { OFF, REVERSED, REPEAT }

    /** Engine v2 P4: which picks of the whole-contract read become advisory findings. */
    public enum AdvisorySpots { ALL, HIGH }

    public PolicyComparisonProperties {
        maxPositions = maxPositions <= 0 ? 8 : maxPositions;
        flagThreshold = flagThreshold <= 0.0 || flagThreshold > 1.0 ? 0.6 : flagThreshold;
        uncoveredThreshold = uncoveredThreshold <= 0.0 || uncoveredThreshold > 1.0
                ? 0.5 : uncoveredThreshold;
        orderProbe = orderProbe == null ? OrderProbe.OFF : orderProbe;
        searchLimit = searchLimit <= 0 ? 3 : searchLimit;
        advisorySpots = advisorySpots == null ? AdvisorySpots.ALL : advisorySpots;
    }
}
