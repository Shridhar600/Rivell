package com.rivell.workbench.contracts;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The typed semantic interpretation of one clause region of a contract version.
 * Keeps source text untouched; concepts, facts, machine proposals, and human
 * corrections coexist (AD #5.17/#5.18).
 */
public record ClauseSemantics(
        UUID id,
        UUID contractVersionId,
        UUID headingNodeId,
        List<UUID> textNodeIds,
        String contentText,
        ConceptClassification classification,
        List<LegalFact> facts,
        List<Interpretation> interpretations,
        FactCorrection correction,
        UUID correctedBy) {

    public ClauseSemantics {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(contractVersionId, "contractVersionId");
        Objects.requireNonNull(headingNodeId, "headingNodeId");
        textNodeIds = textNodeIds == null ? List.of() : List.copyOf(textNodeIds);
        Objects.requireNonNull(classification, "classification");
        facts = facts == null ? List.of() : List.copyOf(facts);
        interpretations = interpretations == null ? List.of() : List.copyOf(interpretations);
        Objects.requireNonNull(correction, "correction");
    }

    public boolean conceptCorrected() {
        return correction.corrected() && correction.primaryConcept() != null;
    }

    public java.util.Optional<LegalConcept> primaryConcept() {
        return classification.primaryConcept();
    }

    public List<LegalConcept> secondaryConcepts() {
        return classification.secondaryConcepts();
    }

    public boolean factCorrected() {
        return correction.corrected() && correction.factValues() != null
                && !correction.factValues().isEmpty();
    }

    public ClauseSemantics withClassification(ConceptClassification classification) {
        return new ClauseSemantics(id, contractVersionId, headingNodeId, textNodeIds,
                contentText, classification, facts, interpretations, correction, correctedBy);
    }

    public ClauseSemantics withCorrection(FactCorrection correction, UUID correctedBy) {
        return new ClauseSemantics(id, contractVersionId, headingNodeId, textNodeIds,
                contentText, classification, facts, interpretations, correction, correctedBy);
    }
}
