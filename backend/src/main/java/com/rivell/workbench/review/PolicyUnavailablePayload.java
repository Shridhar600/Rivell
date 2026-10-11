package com.rivell.workbench.review;

import java.util.Objects;

/**
 * The organization has no published policy for the reviewed subject. {@link #NOT_COVERED}
 * marks a clause no position addresses that the triage judged unusual and against our side.
 */
public record PolicyUnavailablePayload(String sufficiency) implements SignalPayload {

    public static final String NOT_COVERED = "NOT_COVERED_BY_PLAYBOOK";

    public PolicyUnavailablePayload {
        Objects.requireNonNull(sufficiency, "sufficiency");
    }

    @Override
    @com.fasterxml.jackson.annotation.JsonProperty("kind")
    public String kind() {
        return "POLICY_UNAVAILABLE";
    }
}
