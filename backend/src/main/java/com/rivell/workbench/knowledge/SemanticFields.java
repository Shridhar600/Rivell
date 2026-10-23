package com.rivell.workbench.knowledge;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.rivell.workbench.taxonomy.ConceptKey;

/**
 * Machine-proposed semantic fields of a knowledge candidate (M4). The machine proposal is
 * retained even after human correction; effective values prefer the human edit. Fields are
 * typed — never a generic map. Values meaningful per candidate subtype: positions/approval/
 * escalation trigger for playbook rules, variant role for clause variants, concept
 * classification for all families.
 *
 * <p>{@code requiresApprovalCleared} distinguishes "the person cleared this field" from
 * "this correction did not touch the field". Both arrive as a null value, and a null value
 * alone must keep an older correction's meaning (DW-099: null after an edit means "not
 * specified"). Rows written before this marker existed deserialize with it false, so their
 * correction still falls back to the machine answer.
 */
public record SemanticFields(
        Set<PositionRole> positions,
        Boolean requiresApproval,
        String escalationTrigger,
        PositionRole variantRole,
        Map<ConceptKey, Double> conceptProbabilities,
        boolean requiresApprovalCleared) {

    public static final SemanticFields EMPTY = new SemanticFields(
            Set.of(), null, null, null, Map.of());

    /** A correction that did not use the cleared marker, for readers of older rows. */
    public SemanticFields(
            Set<PositionRole> positions, Boolean requiresApproval, String escalationTrigger,
            PositionRole variantRole, Map<ConceptKey, Double> conceptProbabilities) {
        this(positions, requiresApproval, escalationTrigger, variantRole, conceptProbabilities,
                false);
    }

    public SemanticFields {
        positions = positions == null ? Set.of() : Set.copyOf(positions);
        conceptProbabilities = conceptProbabilities == null
                ? Map.of()
                : new LinkedHashMap<>(conceptProbabilities);
        // The marker only has meaning for a null value; a present value is itself the answer.
        requiresApprovalCleared = requiresApprovalCleared && requiresApproval == null;
    }

    /** Marks a corrected playbook approval as deliberately "not specified". */
    public SemanticFields withRequiresApprovalCleared() {
        return new SemanticFields(positions, requiresApproval, escalationTrigger, variantRole,
                conceptProbabilities, true);
    }

    public PositionRole effectiveVariantRole() {
        return variantRole == null ? PositionRole.UNSPECIFIED : variantRole;
    }
}
