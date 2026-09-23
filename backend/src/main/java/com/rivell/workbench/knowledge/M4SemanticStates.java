package com.rivell.workbench.knowledge;

import java.util.List;
import java.util.Map;

import com.rivell.workbench.intelligence.SemanticState;

/**
 * Minimal state for M4 bounded semantic decisions. Payloads carry only what the
 * judgment needs — never whole artifacts, tenant ids, or manifests.
 */
final class M4SemanticStates {

    private M4SemanticStates() {
    }

    record ArtifactAssessmentState(
            String declaredType, List<String> sampleTexts) implements SemanticState {

        static final int SCHEMA_VERSION = 1;

        @Override
        public int schemaVersion() {
            return SCHEMA_VERSION;
        }

        @Override
        public Object payload() {
            return Map.of("declaredType", declaredType, "sampleTexts", sampleTexts);
        }
    }

    record PlaybookUnitState(
            String text, String title, ExtractionUnitKind kind,
            Map<String, String> columns) implements SemanticState {

        static final int SCHEMA_VERSION = 1;

        @Override
        public int schemaVersion() {
            return SCHEMA_VERSION;
        }

        @Override
        public Object payload() {
            var payload = new java.util.LinkedHashMap<String, Object>();
            payload.put("text", text);
            if (title != null && !title.isBlank()) {
                payload.put("title", title);
            }
            payload.put("kind", kind.name());
            if (columns != null && !columns.isEmpty()) {
                payload.put("columns", columns);
            }
            return payload;
        }
    }

    record FieldVerificationState(
            String sourceText, String claim) implements SemanticState {

        static final int SCHEMA_VERSION = 1;

        @Override
        public int schemaVersion() {
            return SCHEMA_VERSION;
        }

        @Override
        public Object payload() {
            return Map.of("sourceText", sourceText, "claim", claim);
        }
    }

    record ConflictPairState(
            String newText, String newTextRole, String publishedText,
            String publishedTextRole) implements SemanticState {

        static final int SCHEMA_VERSION = 1;

        @Override
        public int schemaVersion() {
            return SCHEMA_VERSION;
        }

        @Override
        public Object payload() {
            var payload = new java.util.LinkedHashMap<String, Object>();
            payload.put("new", Map.of("text", newText, "role", newTextRole));
            payload.put("published", Map.of("text", publishedText, "role", publishedTextRole));
            return payload;
        }
    }
}
