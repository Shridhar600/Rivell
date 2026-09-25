package com.rivell.workbench.review;

import java.util.Objects;

/** Bounded Jev classification of a limitation-of-liability clause against policy. */
public record LiabilityDeviationPayload(
        String deviationType, String capMultiple, String capBasis) implements SignalPayload {

    public LiabilityDeviationPayload {
        Objects.requireNonNull(deviationType, "deviationType");
    }

    @Override
    @com.fasterxml.jackson.annotation.JsonProperty("kind")
    public String kind() {
        return "LIABILITY_DEVIATION";
    }
}
