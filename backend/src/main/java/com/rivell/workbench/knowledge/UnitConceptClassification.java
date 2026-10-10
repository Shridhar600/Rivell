package com.rivell.workbench.knowledge;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.rivell.workbench.intelligence.DecisionExecutionMetadata;
import com.rivell.workbench.taxonomy.ConceptKey;

/**
 * Semantic classification proposal for one extraction unit: per-topic probabilities,
 * the topics proposed at or above the task threshold, and the taxonomy execution
 * provenance.
 */
public record UnitConceptClassification(
        UUID unitId,
        UUID artifactId,
        List<ConceptKey> proposedConcepts,
        Map<ConceptKey, Double> conceptProbabilities,
        boolean noneMatched,
        int taxonomyVersion,
        List<DecisionExecutionMetadata> executions) {

    public UnitConceptClassification {
        proposedConcepts = List.copyOf(proposedConcepts);
        executions = List.copyOf(executions);
        conceptProbabilities = new LinkedHashMap<>(conceptProbabilities);
    }
}
