package com.rivell.workbench.intelligence;

import java.util.Map;

/**
 * Selected option with the provider-computed confidence and the full per-option
 * probability distribution (values approximately sum to 1).
 */
public record ChoiceAnswer(String selected, double confidence, Map<String, Double> probabilities)
        implements SemanticAnswer {

    public ChoiceAnswer {
        if (selected == null || selected.isBlank()) {
            throw new IllegalArgumentException("Selected choice must not be blank");
        }
        if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("Confidence must be a finite value in [0,1]");
        }
        if (probabilities == null || probabilities.isEmpty()) {
            throw new IllegalArgumentException("Probabilities must not be empty");
        }
        probabilities.forEach((key, value) -> {
            if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
                throw new IllegalArgumentException("Probability for " + key + " must be a finite value in [0,1]");
            }
        });
        probabilities = Map.copyOf(probabilities);
    }
}
