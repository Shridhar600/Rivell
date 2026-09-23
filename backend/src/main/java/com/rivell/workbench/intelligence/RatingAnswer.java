package com.rivell.workbench.intelligence;

import java.util.Map;

/**
 * Expected score on the rubric's level line (may fall between integer levels), with the
 * provider-computed confidence and the per-level probability distribution keyed by level number.
 */
public record RatingAnswer(double expectedScore, double confidence, Map<String, Double> levelProbabilities)
        implements SemanticAnswer {

    public RatingAnswer {
        if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("Confidence must be a finite value in [0,1]");
        }
        if (levelProbabilities == null || levelProbabilities.isEmpty()) {
            throw new IllegalArgumentException("Level probabilities must not be empty");
        }
        var maxLevel = -1;
        levelProbabilities.forEach((key, value) -> {
            if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
                throw new IllegalArgumentException("Probability for level " + key + " must be a finite value in [0,1]");
            }
        });
        for (var key : levelProbabilities.keySet()) {
            try {
                maxLevel = Math.max(maxLevel, Integer.parseInt(key));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Level keys must be numeric: " + key);
            }
        }
        if (!Double.isFinite(expectedScore) || expectedScore < 0.0 || expectedScore > maxLevel) {
            throw new IllegalArgumentException(
                    "Expected score must be a finite value within [0," + maxLevel + "]");
        }
        levelProbabilities = Map.copyOf(levelProbabilities);
    }
}
