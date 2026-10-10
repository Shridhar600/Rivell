package com.rivell.workbench.contracts;

import java.util.Map;

import com.rivell.workbench.intelligence.SemanticState;

/**
 * Minimal states for M5 bounded semantic decisions. Payloads carry only the slice a
 * judgment needs — never a whole document, tenant ids, or provider internals.
 */
final class M5SemanticStates {

    private M5SemanticStates() {
    }

    record ClauseState(String heading, String text) implements SemanticState {

        static final int SCHEMA_VERSION = 1;

        @Override
        public int schemaVersion() {
            return SCHEMA_VERSION;
        }

        @Override
        public Object payload() {
            var payload = new java.util.LinkedHashMap<String, Object>();
            if (heading != null && !heading.isBlank()) {
                payload.put("heading", heading);
            }
            payload.put("text", text);
            return payload;
        }
    }

    record PartyRoleState(String partyName, String contractType,
            String ourSideRole, String excerpt) implements SemanticState {

        static final int SCHEMA_VERSION = 1;

        @Override
        public int schemaVersion() {
            return SCHEMA_VERSION;
        }

        @Override
        public Object payload() {
            var payload = new java.util.LinkedHashMap<String, Object>();
            payload.put("partyName", partyName);
            payload.put("contractType", contractType);
            if (ourSideRole != null && !ourSideRole.isBlank()) {
                payload.put("ourSideRole", ourSideRole);
            }
            payload.put("excerpt", excerpt);
            return payload;
        }
    }

    record FactVerificationState(String clauseText, String claim)
            implements SemanticState {

        static final int SCHEMA_VERSION = 1;

        @Override
        public int schemaVersion() {
            return SCHEMA_VERSION;
        }

        @Override
        public Object payload() {
            return Map.of("clauseText", clauseText, "claim", claim);
        }
    }

    record RelationshipState(String sourceHeading, String sourceText,
            String targetHeading, String targetText) implements SemanticState {

        static final int SCHEMA_VERSION = 1;

        @Override
        public int schemaVersion() {
            return SCHEMA_VERSION;
        }

        @Override
        public Object payload() {
            return Map.of(
                    "source", Map.of("heading", sourceHeading, "text", sourceText),
                    "target", Map.of("heading", targetHeading, "text", targetText));
        }
    }
}
