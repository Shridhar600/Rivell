package com.rivell.workbench.knowledge.api;

import java.util.List;
import java.util.UUID;

import com.rivell.workbench.intelligence.DecisionExecutionMetadata;
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
                        classification.executions().stream().map(ExecutionView::from).toList()));
    }

    public record ConceptView(String concept, double probability) {
    }

    public record MetadataView(int taxonomyVersion, List<ExecutionView> executions) {
    }

    /** One model call behind the proposal: which spec, which model, how long, what it cost. */
    public record ExecutionView(
            UUID executionId,
            String specId,
            int specVersion,
            String providerId,
            String resolvedModel,
            long durationMs,
            int questionCount,
            int inputTokens,
            int outputTokens) {

        private static ExecutionView from(DecisionExecutionMetadata metadata) {
            var usage = metadata.usage();
            return new ExecutionView(metadata.executionId(), metadata.specId(), metadata.specVersion(),
                    metadata.providerId(), metadata.resolvedModel(), metadata.durationMs(),
                    metadata.questionCount(), usage == null ? 0 : usage.inputTokens(),
                    usage == null ? 0 : usage.outputTokens());
        }
    }
}
