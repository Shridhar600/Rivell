package com.rivell.workbench.review;

/** More than one applicable published policy position exists; none is chosen. */
public record PolicyAmbiguousPayload(int applicableRuleCount) implements SignalPayload {

    public PolicyAmbiguousPayload {
        if (applicableRuleCount < 2) {
            throw new IllegalArgumentException("ambiguity requires at least two applicable rules");
        }
    }

    @Override
    @com.fasterxml.jackson.annotation.JsonProperty("kind")
    public String kind() {
        return "POLICY_AMBIGUOUS";
    }
}
