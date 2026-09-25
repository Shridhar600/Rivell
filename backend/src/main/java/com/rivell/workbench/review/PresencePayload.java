package com.rivell.workbench.review;

import java.util.Objects;

/** Observation of a concept-presence check outcome (expectation from the plan). */
public record PresencePayload(CheckExpectation expectation) implements SignalPayload {

    public PresencePayload {
        Objects.requireNonNull(expectation, "expectation");
    }

    @Override
    @com.fasterxml.jackson.annotation.JsonProperty("kind")
    public String kind() {
        return "PRESENCE";
    }
}
