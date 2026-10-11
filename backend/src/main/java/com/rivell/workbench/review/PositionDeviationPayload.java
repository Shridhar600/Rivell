package com.rivell.workbench.review;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Code-owned outcome of one judged position: category, flag probability, distribution. */
public record PositionDeviationPayload(
        UUID positionCandidateId,
        String category,
        double flagProbability,
        Map<String, Double> probabilities) implements SignalPayload {

    public PositionDeviationPayload {
        Objects.requireNonNull(positionCandidateId, "positionCandidateId");
        Objects.requireNonNull(category, "category");
        probabilities = probabilities == null ? Map.of() : Map.copyOf(probabilities);
    }

    @Override
    @com.fasterxml.jackson.annotation.JsonProperty("kind")
    public String kind() {
        return "POSITION_DEVIATION";
    }
}
