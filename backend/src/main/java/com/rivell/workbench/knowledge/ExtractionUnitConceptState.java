package com.rivell.workbench.knowledge;

import com.rivell.workbench.intelligence.SemanticState;

import java.util.Map;

/**
 * Minimal state for legal-concept classification of one extraction unit: the unit text
 * plus only the structural context the judgment needs. Schema v1. The payload never
 * contains whole artifacts or tenant identifiers.
 */
public record ExtractionUnitConceptState(
        String text, ExtractionUnitKind kind, String title) implements SemanticState {

    static final int SCHEMA_VERSION = 1;

    @Override
    public int schemaVersion() {
        return SCHEMA_VERSION;
    }

    @Override
    public Object payload() {
        var payload = new java.util.LinkedHashMap<String, Object>();
        payload.put("text", text);
        payload.put("kind", kind.name());
        if (title != null && !title.isBlank()) {
            payload.put("title", title);
        }
        return Map.copyOf(payload);
    }
}
