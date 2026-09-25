package com.rivell.workbench.intelligence;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * A versioned bounded generative extraction: which typed fields to produce from a state.
 * Fields are named and described; values are plain strings validated against the spec.
 * `allowedValues` constrains a field to a closed enum; `listFields` declares fields the
 * model may answer as a JSON array of strings. Changed fields or descriptions require a
 * version bump.
 */
public record ReasoningSpec(
        String id,
        int version,
        String purpose,
        int stateSchemaVersion,
        Map<String, String> fields,
        Map<String, Set<String>> allowedValues,
        Set<String> listFields) implements SemanticAsset {

    public ReasoningSpec(
            String id, int version, String purpose, int stateSchemaVersion,
            Map<String, String> fields) {
        this(id, version, purpose, stateSchemaVersion, fields, Map.of(), Set.of());
    }

    public ReasoningSpec(
            String id, int version, String purpose, int stateSchemaVersion,
            Map<String, String> fields, Map<String, Set<String>> allowedValues) {
        this(id, version, purpose, stateSchemaVersion, fields, allowedValues, Set.of());
    }

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
        allowedValues = allowedValues == null ? Map.of() : Map.copyOf(allowedValues);
        for (var name : allowedValues.keySet()) {
            if (!fields.containsKey(name)) {
                throw new IllegalArgumentException(
                        "Allowed values declared for unknown field " + name);
            }
        }
        listFields = listFields == null ? Set.of() : Set.copyOf(listFields);
        for (var name : listFields) {
            if (!fields.containsKey(name)) {
                throw new IllegalArgumentException(
                        "List shape declared for unknown field " + name);
            }
        }
    }
}
