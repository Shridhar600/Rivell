package com.rivell.workbench.knowledge;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.rivell.workbench.contracts.LegalConcept;

/**
 * Machine-proposed semantic fields of a knowledge candidate (M4). The machine proposal is
 * retained even after human correction; effective values prefer the human edit. Fields are
 * typed — never a generic map. Values meaningful per candidate subtype: positions/approval/
 * escalation trigger for playbook rules, variant role for clause variants, concept
 * classification for all families.
 */
public record SemanticFields(
        Set<PositionRole> positions,
        Boolean requiresApproval,
        String escalationTrigger,
        PositionRole variantRole,
        Map<LegalConcept, Double> conceptProbabilities) {

    public static final SemanticFields EMPTY = new SemanticFields(
            Set.of(), null, null, null, Map.of());

    public SemanticFields {
        positions = positions == null ? Set.of() : Set.copyOf(positions);
        conceptProbabilities = conceptProbabilities == null
                ? Map.of()
                : new LinkedHashMap<>(conceptProbabilities);
    }

    public PositionRole effectiveVariantRole() {
        return variantRole == null ? PositionRole.UNSPECIFIED : variantRole;
    }

    /**
     * Highest-probability concept meeting the task threshold; empty when none qualifies
     * (an honest no-match, never fabricated certainty).
     */
    public static Optional<LegalConcept> primaryConcept(
            Map<LegalConcept, Double> probabilities, double threshold) {
        return probabilities.entrySet().stream()
                .filter(entry -> entry.getValue() >= threshold)
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey);
    }
}
