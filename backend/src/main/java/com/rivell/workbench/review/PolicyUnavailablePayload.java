package com.rivell.workbench.review;

import java.util.Objects;

/** The organization has no published policy for the reviewed concept. */
public record PolicyUnavailablePayload(String sufficiency) implements SignalPayload {

    public PolicyUnavailablePayload {
        Objects.requireNonNull(sufficiency, "sufficiency");
    }

    @Override
    @com.fasterxml.jackson.annotation.JsonProperty("kind")
    public String kind() {
        return "POLICY_UNAVAILABLE";
    }
}
