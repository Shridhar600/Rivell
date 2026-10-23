package com.rivell.workbench.contracts;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.rivell.workbench.taxonomy.ConceptKey;

/**
 * Concept membership outcome of one clause region: independent concept members
 * (multi-concept honest), the bounded primary choice over members, and the raw
 * probabilities. An empty primary is legitimate (uncertainty is not forced).
 * {@code headingTopic} is kept apart from the primary and only presence coverage reads it.
 */
public record ConceptClassification(
        List<ConceptKey> concepts,
        List<ConceptKey> secondaryConcepts,
        Map<ConceptKey, Double> probabilities,
        ConceptKey headingTopic) {

    public ConceptClassification(
            List<ConceptKey> concepts,
            List<ConceptKey> secondaryConcepts,
            Map<ConceptKey, Double> probabilities) {
        this(concepts, secondaryConcepts, probabilities, null);
    }

    public ConceptClassification {
        concepts = concepts == null ? List.of() : List.copyOf(concepts);
        secondaryConcepts = secondaryConcepts == null
                ? List.of() : List.copyOf(secondaryConcepts);
        probabilities = probabilities == null ? Map.of() : Map.copyOf(probabilities);
        if (headingTopic != null && !concepts.contains(headingTopic)) {
            throw new IllegalArgumentException(
                    "Heading topic " + headingTopic + " is not among the concepts");
        }
    }

    public Optional<ConceptKey> primaryConcept() {
        return concepts.stream()
                .filter(concept -> !secondaryConcepts.contains(concept))
                .findFirst();
    }
}
