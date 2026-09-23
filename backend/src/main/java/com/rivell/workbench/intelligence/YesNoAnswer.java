package com.rivell.workbench.intelligence;

/**
 * Probability of a true answer, 0–1. Values near 0.5 indicate ambiguity.
 */
public record YesNoAnswer(double probability) implements SemanticAnswer {

    public YesNoAnswer {
        if (!Double.isFinite(probability) || probability < 0.0 || probability > 1.0) {
            throw new IllegalArgumentException("Probability must be a finite value in [0,1]");
        }
    }
}
