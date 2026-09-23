package com.rivell.workbench.contracts;

import java.util.Map;
import java.util.Objects;

/**
 * One typed legal fact extracted from a clause region. The schema defines the shape;
 * every value is validated against it at construction. Facts carry no acceptability
 * judgment — that is M7 territory, never M5.
 */
public record LegalFact(String factSchema, int schemaVersion, Map<String, String> values) {

    public LegalFact {
        Objects.requireNonNull(factSchema, "factSchema");
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("Schema version must be >= 1");
        }
        var schema = FactSchema.byId(factSchema);
        if (schema.version() != schemaVersion) {
            throw new IllegalArgumentException("Schema " + factSchema + " is v"
                    + schema.version() + ", received v" + schemaVersion);
        }
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("A legal fact requires at least one value");
        }
        for (var entry : values.entrySet()) {
            if (!schema.fields().contains(entry.getKey())) {
                throw new IllegalArgumentException(
                        "Field " + entry.getKey() + " is not part of schema " + factSchema);
            }
            schema.validate(entry.getKey(), entry.getValue());
        }
        values = Map.copyOf(values);
    }
}
