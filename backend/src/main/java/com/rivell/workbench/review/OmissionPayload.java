package com.rivell.workbench.review;

import java.util.Objects;
import java.util.UUID;

/**
 * Observation of one published playbook position no clause of the contract addresses
 * (engine v2 omissions lane): the candidate, its label and how many contract clauses
 * were searched for it.
 */
public record OmissionPayload(UUID candidateId, String label, int searchedClauses)
        implements SignalPayload {

    public OmissionPayload {
        Objects.requireNonNull(candidateId, "candidateId");
        Objects.requireNonNull(label, "label");
    }

    @Override
    @com.fasterxml.jackson.annotation.JsonProperty("kind")
    public String kind() {
        return "OMISSION";
    }
}
