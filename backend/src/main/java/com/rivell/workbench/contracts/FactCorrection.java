package com.rivell.workbench.contracts;

import java.util.Map;
import java.util.UUID;

import com.rivell.workbench.taxonomy.ConceptKey;

/**
 * Human correction of one clause's semantic interpretation. Either a primary-concept
 * correction or a fact-values correction; the machine proposal is never erased.
 * Patches are merge semantics: a null fact value clears that field's correction;
 * null primaryConcept leaves the concept untouched, blank clears it.
 */
public record FactCorrection(String primaryConcept, Map<String, String> factValues, UUID correctionId) {

    public static final FactCorrection NONE = new FactCorrection(null, Map.of(), null);

    public FactCorrection {
        if (factValues == null) {
            factValues = Map.of();
        } else {
            var copy = new java.util.LinkedHashMap<String, String>();
            for (var entry : factValues.entrySet()) {
                copy.put(entry.getKey(), entry.getValue());
            }
            factValues = java.util.Collections.unmodifiableMap(copy);
        }
    }

    public static FactCorrection ofPrimary(ConceptKey concept) {
        return new FactCorrection(concept.value(), Map.of(), UUID.randomUUID());
    }

    public static FactCorrection ofFacts(Map<String, String> values) {
        return new FactCorrection(null, values, UUID.randomUUID());
    }

    public boolean corrected() {
        return correctionId != null;
    }
}
