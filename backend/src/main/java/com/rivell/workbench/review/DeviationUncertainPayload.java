package com.rivell.workbench.review;

import java.util.Objects;

/** The bounded path cannot classify the clause against policy; human review required. */
public record DeviationUncertainPayload(String reason) implements SignalPayload {

    public DeviationUncertainPayload {
        Objects.requireNonNull(reason, "reason");
    }

    @Override
    @com.fasterxml.jackson.annotation.JsonProperty("kind")
    public String kind() {
        return "DEVIATION_UNCERTAIN";
    }
}
