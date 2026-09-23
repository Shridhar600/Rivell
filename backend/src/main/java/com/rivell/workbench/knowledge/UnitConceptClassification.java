package com.rivell.workbench.knowledge;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.rivell.workbench.contracts.LegalConcept;
import com.rivell.workbench.intelligence.DecisionExecutionMetadata;

/**
 * Semantic classification proposal for one extraction unit: per-concept probabilities,
 * the concepts proposed above the task threshold, and the execution provenance.
 */
public record UnitConceptClassification(
        UUID unitId,
        UUID artifactId,
        List<LegalConcept> proposedConcepts,
        Map<LegalConcept, Double> conceptProbabilities,
        boolean noneMatched,
        DecisionExecutionMetadata metadata) {

    public UnitConceptClassification {
        proposedConcepts = List.copyOf(proposedConcepts);
        conceptProbabilities = new LinkedHashMap<>(conceptProbabilities);
    }
}
