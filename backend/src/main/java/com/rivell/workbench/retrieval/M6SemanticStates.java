package com.rivell.workbench.retrieval;

import java.util.LinkedHashMap;
import java.util.List;

import com.rivell.workbench.intelligence.SemanticState;

/**
 * Minimal states for M6 retrieval judgments. Payloads carry only the slice a
 * judgment needs — never a whole document or provider internals.
 */
final class M6SemanticStates {

    private M6SemanticStates() {
    }

    record PrecedentRelevanceState(
            String subjectText,
            String subjectConcept,
            String precedentText,
            String precedentConcept) implements SemanticState {

        static final int SCHEMA_VERSION = 1;

        @Override
        public int schemaVersion() {
            return SCHEMA_VERSION;
        }

        @Override
        public Object payload() {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("subjectText", subjectText);
            payload.put("subjectConcept", subjectConcept);
            payload.put("precedentText", precedentText);
            payload.put("precedentConcept", precedentConcept);
            return payload;
        }
    }

    record PolicyCompatibilityState(
            String precedentText,
            List<String> policyTexts) implements SemanticState {

        static final int SCHEMA_VERSION = 1;

        @Override
        public int schemaVersion() {
            return SCHEMA_VERSION;
        }

        @Override
        public Object payload() {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("precedentText", precedentText);
            payload.put("policyTexts", policyTexts);
            return payload;
        }
    }
}
