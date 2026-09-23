package com.rivell.workbench.intelligence;

/**
 * A versioned semantic decision: software behavior, not an ad-hoc prompt. A changed
 * question, criterion, or purpose requires a new spec version.
 */
public record DecisionSpec(String id, int version, String purpose, int stateSchemaVersion, QuestionPack pack)
        implements SemanticAsset {

    public DecisionSpec {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Spec id must not be blank");
        }
        if (version < 1) {
            throw new IllegalArgumentException("Spec version must be >= 1");
        }
        if (purpose == null || purpose.isBlank()) {
            throw new IllegalArgumentException("Spec purpose must not be blank");
        }
        if (stateSchemaVersion < 1) {
            throw new IllegalArgumentException("State schema version must be >= 1");
        }
    }
}
