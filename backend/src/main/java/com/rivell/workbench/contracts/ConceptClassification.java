package com.rivell.workbench.contracts;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Concept membership outcome of one clause region: independent concept members
 * (multi-concept honest), the bounded primary choice over members, and the raw
 * probabilities. An empty primary is legitimate (uncertainty is not forced).
 */
public record ConceptClassification(
        List<LegalConcept> concepts,
        List<LegalConcept> secondaryConcepts,
        Map<LegalConcept, Double> probabilities) {

    public ConceptClassification {
        concepts = concepts == null ? List.of() : List.copyOf(concepts);
        secondaryConcepts = secondaryConcepts == null
                ? List.of() : List.copyOf(secondaryConcepts);
        probabilities = probabilities == null ? Map.of() : Map.copyOf(probabilities);
    }

    public Optional<LegalConcept> primaryConcept() {
        return concepts.stream()
                .filter(concept -> !secondaryConcepts.contains(concept))
                .findFirst();
    }
}
