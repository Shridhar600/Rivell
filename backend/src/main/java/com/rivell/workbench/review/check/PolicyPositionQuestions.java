package com.rivell.workbench.review.check;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.rivell.workbench.contracts.ClauseSemantics;
import com.rivell.workbench.intelligence.SemanticQuestion;
import com.rivell.workbench.retrieval.EvidenceItem;
import com.rivell.workbench.review.PolicyComparisonSpecs;
import com.rivell.workbench.taxonomy.ConceptRegistry;

/**
 * One Choice question per resolved position, keyed {@code position_1..n} so code maps
 * answers back by index (no candidate ids in keys). Instructions reuse the measured
 * probe wording: topic label plus the role-tagged fields in field order; a candidate
 * without role fields sends its content text as the position.
 */
public final class PolicyPositionQuestions {

    static final String KEY_PREFIX = "position_";

    private PolicyPositionQuestions() {
    }

    public static String key(int index) {
        return KEY_PREFIX + (index + 1);
    }

    public static Map<String, SemanticQuestion> build(
            List<EvidenceItem> positions, ClauseSemantics clause, ConceptRegistry registry) {
        return build(positions, clause, registry, false);
    }

    /** {@code reversed}: options in reverse order, for the order probe (engine v2 P0). */
    public static Map<String, SemanticQuestion> build(
            List<EvidenceItem> positions, ClauseSemantics clause, ConceptRegistry registry,
            boolean reversed) {
        var questions = new LinkedHashMap<String, SemanticQuestion>();
        for (int index = 0; index < positions.size(); index++) {
            questions.put(key(index), PolicyComparisonSpecs.positionQuestion(
                    instructions(positions.get(index), clause, registry), reversed));
        }
        return questions;
    }

    static String instructions(
            EvidenceItem position, ClauseSemantics clause, ConceptRegistry registry) {
        var parts = new ArrayList<String>();
        parts.add("Compare the clause with our playbook position on "
                + topicLabel(position, clause, registry) + ".");
        var facts = position.policyFacts();
        var preferred = facts == null ? null : facts.preferred();
        var fallbacks = facts == null ? List.<String>of() : facts.fallbacks();
        var prohibited = facts == null ? null : facts.prohibited();
        if (preferred != null) {
            parts.add("Preferred: " + preferred);
        }
        for (int index = 0; index < fallbacks.size(); index++) {
            parts.add("Fallback " + (index + 1) + ": " + fallbacks.get(index));
        }
        if (prohibited != null) {
            parts.add("Walk-away: " + prohibited);
        }
        if (preferred == null && fallbacks.isEmpty() && prohibited == null) {
            parts.add("Position: " + position.text());
        }
        return String.join("\n", parts);
    }

    private static String topicLabel(
            EvidenceItem position, ClauseSemantics clause, ConceptRegistry registry) {
        var concept = position.legalConcept() != null
                ? position.legalConcept()
                : clause.effectivePrimaryConcept().orElse(null);
        if (concept != null) {
            try {
                return registry.require(concept).label();
            } catch (RuntimeException ignored) {
                // a key the registry does not know falls back to a neutral label
            }
        }
        return "this subject";
    }
}
