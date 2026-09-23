package com.rivell.workbench.contracts;

import java.util.Map;
import java.util.UUID;

/**
 * Human correction of one clause's semantic interpretation. Either a primary-concept
 * correction or a fact-values correction; the machine proposal is never erased.
 */
public record FactCorrection(String primaryConcept, Map<String, String> factValues, UUID correctionId) {

    public static final FactCorrection NONE = new FactCorrection(null, Map.of(), null);

    public FactCorrection {
        factValues = factValues == null ? Map.of() : Map.copyOf(factValues);
    }

    public static FactCorrection ofPrimary(LegalConcept concept) {
        return new FactCorrection(concept.name(), Map.of(), UUID.randomUUID());
    }

    public static FactCorrection ofFacts(Map<String, String> values) {
        return new FactCorrection(null, values, UUID.randomUUID());
    }

    public boolean corrected() {
        return correctionId != null;
    }
}
