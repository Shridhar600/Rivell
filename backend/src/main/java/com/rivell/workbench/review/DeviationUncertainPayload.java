package com.rivell.workbench.review;

import java.util.Objects;

/** The bounded path cannot classify the clause against policy; human review required. */
public record DeviationUncertainPayload(String reason) implements SignalPayload {

    /** Engine v2: Jev and the second opinion disagree on whether the clause breaks the position. */
    public static final String SECOND_OPINION_DISAGREES = "second_opinion_disagrees";
    /** Engine v2: the second opinion failed, answered outside the rules, or the budget ran out. */
    public static final String SECOND_OPINION_UNAVAILABLE = "second_opinion_unavailable";

    public DeviationUncertainPayload {
        Objects.requireNonNull(reason, "reason");
    }

    @Override
    @com.fasterxml.jackson.annotation.JsonProperty("kind")
    public String kind() {
        return "DEVIATION_UNCERTAIN";
    }
}
