package com.rivell.workbench.intelligence;

/**
 * Yes/no judgment. The returned probability IS the certainty measure — the provider
 * deliberately returns no separate confidence for this primitive (research: docs/confidence).
 */
public record YesNoQuestion(String instructions, String trueCriterion, String falseCriterion)
        implements SemanticQuestion {

    public YesNoQuestion {
        if (instructions == null || instructions.isBlank()) {
            throw new IllegalArgumentException("Instructions must not be blank");
        }
    }
}
