package com.rivell.workbench.knowledge.api;

import java.util.List;
import java.util.UUID;

import com.rivell.workbench.knowledge.UnitConceptClassification;

/**
 * Domain-meaning view of a semantic classification proposal: per-topic probabilities,
 * topics proposed at or above the task threshold, and taxonomy execution provenance.
 * No provider internals.
 */
public record UnitConceptClassificationView(
        UUID unitId,
        UUID artifactId,
        List<ConceptView> proposedConcepts,
        List<ConceptView> evaluatedConcepts,
        boolean noneMatched,
        MetadataView metadata) {

    public static UnitConceptClassificationView from(UnitConceptClassification classification) {
        var proposed = classification.proposedConcepts().stream()
                .map(concept -> new ConceptView(concept.value(),
                        classification.conceptProbabilities().getOrDefault(concept, 0.0)))
                .toList();
        var evaluated = classification.conceptProbabilities().entrySet().stream()
                .map(entry -> new ConceptView(entry.getKey().value(), entry.getValue()))
                .toList();
        return new UnitConceptClassificationView(
                classification.unitId(), classification.artifactId(),
                proposed, evaluated, classification.noneMatched(),
                new MetadataView(classification.taxonomyVersion(),
                        classification.decisionExecutions(), classification.questions()));
    }

    public record ConceptView(String concept, double probability) {
    }

    public record MetadataView(
            int taxonomyVersion,
            int decisionExecutions,
            int questions) {
    }
}
