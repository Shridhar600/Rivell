package com.rivell.workbench.intelligence;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A versioned bounded generative extraction: which typed fields to produce from a state.
 * Fields are named and described; values are plain strings validated against the spec.
 * Changed fields or descriptions require a version bump.
 */
public record ReasoningSpec(
        String id,
        int version,
        String purpose,
        int stateSchemaVersion,
        Map<String, String> fields) implements SemanticAsset {

    public ReasoningSpec {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Reasoning spec id must not be blank");
        }
        if (version < 1) {
            throw new IllegalArgumentException("Reasoning spec version must be >= 1");
        }
        if (purpose == null || purpose.isBlank()) {
            throw new IllegalArgumentException("Purpose must not be blank");
        }
        if (stateSchemaVersion < 1) {
            throw new IllegalArgumentException("State schema version must be >= 1");
        }
        if (fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException("A reasoning spec requires at least one field");
        }
        fields = new LinkedHashMap<>(fields);
    }
}
