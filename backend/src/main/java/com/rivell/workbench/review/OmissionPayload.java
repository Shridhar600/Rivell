package com.rivell.workbench.review;

import java.util.Objects;
import java.util.UUID;

/**
 * Observation of one published playbook position no clause of the contract addresses
 * (engine v2 omissions lane and the R3-002 walk-away absence check): the candidate, its
 * label and how many contract clauses were searched for it. {@code walkAway} is the
 * position's walk-away text when the absence check produced the observation, else null.
 */
public record OmissionPayload(UUID candidateId, String label, int searchedClauses, String walkAway)
        implements SignalPayload {

    public OmissionPayload {
        Objects.requireNonNull(candidateId, "candidateId");
        Objects.requireNonNull(label, "label");
    }

    /** A presence-lane omission: the "does this position require a provision" trigger. */
    public OmissionPayload(UUID candidateId, String label, int searchedClauses) {
        this(candidateId, label, searchedClauses, null);
    }

    @Override
    @com.fasterxml.jackson.annotation.JsonProperty("kind")
    public String kind() {
        return "OMISSION";
    }
}
