package com.rivell.workbench.knowledge.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.rivell.workbench.knowledge.LegalConcept;
import com.rivell.workbench.knowledge.UnitConceptClassification;

/**
 * Domain-meaning view of a semantic classification proposal: per-concept probabilities,
 * concepts proposed above the task threshold, and execution provenance. No provider internals.
 */
public record UnitConceptClassificationView(
        java.util.UUID unitId,
        java.util.UUID artifactId,
        List<ConceptView> proposedConcepts,
        List<ConceptView> evaluatedConcepts,
        boolean noneMatched,
        MetadataView metadata) {

    public static UnitConceptClassificationView from(UnitConceptClassification classification) {
        var proposed = classification.proposedConcepts().stream()
                .map(concept -> conceptView(concept, classification))
                .toList();
        var evaluated = classification.conceptProbabilities().entrySet().stream()
                .map(entry -> new ConceptView(entry.getKey(), entry.getValue()))
                .toList();
        return new UnitConceptClassificationView(
                classification.unitId(), classification.artifactId(),
                proposed, evaluated, classification.noneMatched(),
                MetadataView.from(classification.metadata()));
    }

    private static ConceptView conceptView(
            LegalConcept concept, UnitConceptClassification classification) {
        return new ConceptView(concept, classification.conceptProbabilities().get(concept));
    }

    public record ConceptView(LegalConcept concept, double probability) {
    }

    public record MetadataView(
            java.util.UUID executionId,
            String specId,
            int specVersion,
            int stateSchemaVersion,
            String providerId,
            String requestedModelAlias,
            String resolvedModel,
            long durationMs,
            int questionCount,
            int inputTokens,
            int outputTokens) {

        private static MetadataView from(com.rivell.workbench.intelligence.DecisionExecutionMetadata metadata) {
            var usage = metadata.usage();
            return new MetadataView(
                    metadata.executionId(), metadata.specId(), metadata.specVersion(),
                    metadata.stateSchemaVersion(), metadata.providerId(),
                    metadata.requestedModelAlias(), metadata.resolvedModel(),
                    metadata.durationMs(), metadata.questionCount(),
                    usage == null ? 0 : usage.inputTokens(),
                    usage == null ? 0 : usage.outputTokens());
        }
    }
}
