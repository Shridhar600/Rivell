package com.rivell.workbench.contracts;

import java.util.ArrayList;
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
        ContractRegionSegmenter.SemanticRegionKind regionKind,
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

    /**
     * Human-corrected primary concept when one is active, else the machine proposal
     * (AD #5.18: corrections override without erasing machine history). Downstream
     * flows (retrieval planning, review) consume the effective concept.
     */
    public java.util.Optional<LegalConcept> effectivePrimaryConcept() {
        if (conceptCorrected()) {
            try {
                return java.util.Optional.of(
                        LegalConcept.valueOf(correction.primaryConcept()));
            } catch (IllegalArgumentException e) {
                return classification.primaryConcept();
            }
        }
        return classification.primaryConcept();
    }

    public List<LegalConcept> secondaryConcepts() {
        return classification.secondaryConcepts();
    }

    /**
     * Effective fact view: human-corrected values replace the machine proposal per
     * field; uncorrected fields keep the machine value. Machine history is never
     * erased (correction layering per AD #5.18).
     */
    public List<LegalFact> effectiveFacts() {
        if (!factCorrected()) {
            return facts;
        }
        var effective = new ArrayList<LegalFact>();
        for (var fact : facts) {
            var values = new java.util.LinkedHashMap<String, String>(fact.values());
            var schema = FactSchema.byId(fact.factSchema());
            for (var entry : correction.factValues().entrySet()) {
                if (schema.fields().contains(entry.getKey())) {
                    values.put(entry.getKey(), entry.getValue());
                }
            }
            effective.add(new LegalFact(fact.factSchema(), fact.schemaVersion(), values));
        }
        return effective;
    }

    public boolean factCorrected() {
        return correction.corrected() && correction.factValues() != null
                && !correction.factValues().isEmpty();
    }

    public ClauseSemantics withClassification(ConceptClassification classification) {
        return new ClauseSemantics(id, contractVersionId, headingNodeId, regionKind, textNodeIds,
                contentText, classification, facts, interpretations, correction, correctedBy);
    }

    public ClauseSemantics withCorrection(FactCorrection correction, UUID correctedBy) {
        return new ClauseSemantics(id, contractVersionId, headingNodeId, regionKind, textNodeIds,
                contentText, classification, facts, interpretations, correction, correctedBy);
    }
}
