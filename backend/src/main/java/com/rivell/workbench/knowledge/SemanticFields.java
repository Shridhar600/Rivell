package com.rivell.workbench.knowledge;

import java.util.LinkedHashMap;
import java.util.List;
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
 * <p>The typed stance values ({@code preferredText}, {@code fallbackTexts}, {@code
 * prohibitedText}) and the typed {@code authorityRules} are what the labelled-field stage read
 * from the source. They are kept beside the positions so review can compare the contract with
 * the words the playbook actually states, and so an approval exists only when a typed rule
 * supports it. {@code unmappedAuthority} holds the label-shaped walk-away or approver words the
 * stage could not map; it is what keeps such a draft out of "Review all".
 *
 * <p>{@code requiresApprovalCleared} distinguishes "the person cleared this field" from
 * "this correction did not touch the field". Both arrive as a null value, and a null value
 * alone must keep an older correction's meaning (null after an edit means "not
 * specified"). Rows written before this marker existed deserialize with it false, so their
 * correction still falls back to the machine answer. {@code authorityRulesCleared} does the
 * same for the rule list, where an empty list is a deliberate clear; {@code positionsCleared}
 * and {@code escalationTriggerCleared} do it for an emptied position set and a blank trigger.
 */
public record SemanticFields(
        Set<PositionRole> positions,
        Boolean requiresApproval,
        String escalationTrigger,
        PositionRole variantRole,
        Map<ConceptKey, Double> conceptProbabilities,
        boolean requiresApprovalCleared,
        String preferredText,
        List<String> fallbackTexts,
        String prohibitedText,
        List<AuthorityRule> authorityRules,
        List<AuthorityConflict> authorityConflicts,
        boolean authorityRulesCleared,
        List<String> unmappedAuthority,
        /** A corrected fallback list was deliberately cleared, not left untouched. */
        boolean fallbackTextsCleared,
        /** A corrected position set was deliberately cleared, not left untouched. */
        boolean positionsCleared,
        /** A corrected escalation trigger was deliberately blanked, not left untouched. */
        boolean escalationTriggerCleared) {

    public static final SemanticFields EMPTY = new SemanticFields(
            Set.of(), null, null, null, Map.of());

    /** A correction that did not use the cleared markers, for readers of older rows. */
    public SemanticFields(
            Set<PositionRole> positions, Boolean requiresApproval, String escalationTrigger,
            PositionRole variantRole, Map<ConceptKey, Double> conceptProbabilities) {
        this(positions, requiresApproval, escalationTrigger, variantRole, conceptProbabilities,
                false, null, List.of(), null, List.of(), List.of(), false, List.of(), false,
                false, false);
    }

    /** A correction from before the position and escalation markers existed. */
    public SemanticFields(
            Set<PositionRole> positions, Boolean requiresApproval, String escalationTrigger,
            PositionRole variantRole, Map<ConceptKey, Double> conceptProbabilities,
            boolean requiresApprovalCleared, String preferredText, List<String> fallbackTexts,
            String prohibitedText, List<AuthorityRule> authorityRules,
            List<AuthorityConflict> authorityConflicts, boolean authorityRulesCleared,
            List<String> unmappedAuthority, boolean fallbackTextsCleared) {
        this(positions, requiresApproval, escalationTrigger, variantRole, conceptProbabilities,
                requiresApprovalCleared, preferredText, fallbackTexts, prohibitedText,
                authorityRules, authorityConflicts, authorityRulesCleared, unmappedAuthority,
                fallbackTextsCleared, false, false);
    }

    public SemanticFields {
        positions = positions == null ? Set.of() : Set.copyOf(positions);
        conceptProbabilities = conceptProbabilities == null
                ? Map.of()
                : new LinkedHashMap<>(conceptProbabilities);
        preferredText = preferredText == null ? null : preferredText.strip();
        fallbackTexts = fallbackTexts == null ? List.of()
                : fallbackTexts.stream()
                        .filter(text -> text != null && !text.isBlank())
                        .map(String::strip)
                        .toList();
        prohibitedText = prohibitedText == null ? null : prohibitedText.strip();
        authorityRules = authorityRules == null ? List.of() : List.copyOf(authorityRules);
        authorityConflicts = authorityConflicts == null ? List.of() : List.copyOf(authorityConflicts);
        unmappedAuthority = unmappedAuthority == null ? List.of() : List.copyOf(unmappedAuthority);
        // The marker only has meaning for an empty value; a present value is itself the answer.
        requiresApprovalCleared = requiresApprovalCleared && requiresApproval == null;
        authorityRulesCleared = authorityRulesCleared && authorityRules.isEmpty();
        fallbackTextsCleared = fallbackTextsCleared && fallbackTexts.isEmpty();
        positionsCleared = positionsCleared && positions.isEmpty();
        escalationTriggerCleared = escalationTriggerCleared
                && (escalationTrigger == null || escalationTrigger.isBlank());
        escalationTrigger = escalationTriggerCleared ? null : escalationTrigger;
    }

    /** Marks a corrected playbook approval as deliberately "not specified". */
    public SemanticFields withRequiresApprovalCleared() {
        return new SemanticFields(positions, requiresApproval, escalationTrigger, variantRole,
                conceptProbabilities, true, preferredText, fallbackTexts, prohibitedText,
                authorityRules, authorityConflicts, authorityRulesCleared, unmappedAuthority,
                fallbackTextsCleared, positionsCleared, escalationTriggerCleared);
    }

    /** Marks a corrected approval rule list as deliberately cleared. */
    public SemanticFields withAuthorityRulesCleared() {
        return new SemanticFields(positions, requiresApproval, escalationTrigger, variantRole,
                conceptProbabilities, requiresApprovalCleared, preferredText, fallbackTexts,
                prohibitedText, authorityRules, authorityConflicts, true, unmappedAuthority,
                fallbackTextsCleared, positionsCleared, escalationTriggerCleared);
    }

    /** Marks a corrected position set as deliberately empty. */
    public SemanticFields withPositionsCleared() {
        return new SemanticFields(positions, requiresApproval, escalationTrigger, variantRole,
                conceptProbabilities, requiresApprovalCleared, preferredText, fallbackTexts,
                prohibitedText, authorityRules, authorityConflicts, authorityRulesCleared,
                unmappedAuthority, fallbackTextsCleared, true, escalationTriggerCleared);
    }

    /** Marks a corrected escalation trigger as deliberately blank. */
    public SemanticFields withEscalationTriggerCleared() {
        return new SemanticFields(positions, requiresApproval, escalationTrigger, variantRole,
                conceptProbabilities, requiresApprovalCleared, preferredText, fallbackTexts,
                prohibitedText, authorityRules, authorityConflicts, authorityRulesCleared,
                unmappedAuthority, fallbackTextsCleared, positionsCleared, true);
    }

    public PositionRole effectiveVariantRole() {
        return variantRole == null ? PositionRole.UNSPECIFIED : variantRole;
    }
}
