package com.rivell.workbench.retrieval;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.rivell.workbench.intelligence.ChoiceAnswer;
import com.rivell.workbench.intelligence.ChoiceQuestion;
import com.rivell.workbench.intelligence.DecisionExecutionRequest;
import com.rivell.workbench.intelligence.ProviderExecutionResult;
import com.rivell.workbench.intelligence.SemanticAnswer;
import com.rivell.workbench.intelligence.SemanticDecisionProvider;
import com.rivell.workbench.intelligence.SemanticQuestion;
import com.rivell.workbench.intelligence.TokenUsage;
import com.rivell.workbench.intelligence.YesNoAnswer;
import com.rivell.workbench.intelligence.YesNoQuestion;

/**
 * Deterministic semantic provider for the M6 corpus. Answers every bounded judgment
 * from fixture text cues so the fake behaves like the fixture expectations: concepts
 * classify from distinctive clause language, playbook positions follow source cues,
 * M6 relevance/compatibility judgments follow the labeled precedent scenarios.
 */
public final class CorpusScriptedProvider implements SemanticDecisionProvider {

    @Override
    public String id() {
        return "fake";
    }

    @Override
    public ProviderExecutionResult execute(DecisionExecutionRequest request) {
        var answers = new LinkedHashMap<String, SemanticAnswer>();
        var state = request.state().payload();
        for (var entry : request.pack().questions().entrySet()) {
            var question = entry.getValue();
            if (question instanceof ChoiceQuestion choice) {
                answers.put(entry.getKey(), choiceAnswer(entry.getKey(), choice, state));
            } else {
                answers.put(entry.getKey(),
                        new YesNoAnswer(cueProbability(entry.getKey(), state)));
            }
        }
        return new ProviderExecutionResult(
                answers, "fake-resolved-m6", new TokenUsage(240, 10));
    }

    private ChoiceAnswer choiceAnswer(String name, ChoiceQuestion choice, Object state) {
        var selected = switch (name) {
            case "artifact_class" -> declaredTypeCue(state, choice);
            case "variant_role" -> variantRoleCue(state, choice);
            case "primary_topic" -> primaryConceptCue(state, choice);
            case "governing_law" -> stateText(state).contains("England") ? "UK" : "US";
            default -> choice.criteria().keySet().iterator().next();
        };
        var probabilities = new LinkedHashMap<String, Double>();
        choice.criteria().keySet().forEach(option ->
                probabilities.put(option, option.equals(selected) ? 0.92 : 0.04));
        return new ChoiceAnswer(selected, 0.92, probabilities);
    }

    private String declaredTypeCue(Object state, ChoiceQuestion choice) {
        if (state instanceof Map<?, ?> payload
                && payload.get("declaredType") instanceof String declared
                && choice.criteria().containsKey(declared)) {
            return declared;
        }
        return choice.criteria().keySet().iterator().next();
    }

    private String variantRoleCue(Object state, ChoiceQuestion choice) {
        var title = headingOf(state).toLowerCase();
        if (title.contains("fallback")) {
            return "FALLBACK";
        }
        if (title.contains("preferred") || title.contains("prohibited")) {
            return title.contains("prohibited") ? "PROHIBITED" : "PREFERRED";
        }
        return choice.criteria().keySet().iterator().next();
    }

    /**
     * The fixture fixtures predate the 90-topic taxonomy, so the expected main subject
     * of an ambiguous clause follows the core-concept order the old vocabulary used
     * (the mapped LegalConcept enum order), not the registry's arbitrary leaf order.
     */
    private static final List<String> CORE_ORDER = List.of(
            "CONFIDENTIALITY_OBLIGATIONS", "TERM", "LIMITATION_OF_LIABILITY",
            "INDEMNIFICATION", "GOVERNING_LAW");

    private String primaryConceptCue(Object state, ChoiceQuestion choice) {
        var heading = headingOf(state).toLowerCase();
        var text = stateText(state).toLowerCase();
        var ordered = choice.criteria().keySet().stream()
                .sorted(java.util.Comparator.comparingInt(CorpusScriptedProvider::coreRank))
                .toList();
        for (var option : ordered) {
            var cue = option.toLowerCase().replace('_', ' ');
            if (heading.contains(cue) || heading.contains(firstWord(cue))) {
                return option;
            }
        }
        for (var option : ordered) {
            var cue = option.toLowerCase().replace('_', ' ');
            if (text.contains(cue) || text.contains(firstWord(cue))) {
                return option;
            }
        }
        return ordered.get(0);
    }

    private static int coreRank(String key) {
        var index = CORE_ORDER.indexOf(key);
        return index < 0 ? CORE_ORDER.size() : index;
    }

    private double cueProbability(String questionName, Object state) {
        var text = stateText(state);
        var heading = headingOf(state);
        var combined = (heading + " " + text).toLowerCase();
        return switch (questionName) {
            case "topic_limitation_of_liability" ->
                    combined.contains("overall exposure for damages")
                            || combined.contains("liability") ? 0.95 : 0.05;
            case "topic_confidentiality_obligations" -> combined.contains("confidential") ? 0.95 : 0.05;
            case "topic_term" ->
                    combined.contains("terminat") || combined.contains(" term ")
                            ? 0.95 : 0.05;
            case "topic_indemnification" -> combined.contains("indemnif") ? 0.95 : 0.05;
            case "topic_governing_law" -> combined.contains("govern") ? 0.95 : 0.05;
            case "role_preferred" -> combined.contains("preferred") ? 0.9 : 0.02;
            case "role_fallback" -> combined.contains("fallback") ? 0.9 : 0.02;
            case "role_prohibited" -> combined.contains("prohibited") ? 0.9 : 0.02;
            case "role_escalation" -> combined.contains("escalat") ? 0.9 : 0.02;
            case "role_commentary" -> combined.contains("commentary") ? 0.9 : 0.02;
            case "requires_approval" -> combined.contains("approval") ? 0.9 : 0.02;
            case "narrows_scope" -> combined.contains("united states") ? 0.9 : 0.02;
            case "source_supports" -> 0.95;
            case "role_disclosing_party" -> combined.contains("disclosing") ? 0.95 : 0.05;
            case "role_receiving_party" -> combined.contains("receiving") ? 0.95 : 0.05;
            case "role_customer" -> combined.contains("customer") ? 0.95 : 0.05;
            case "role_supplier" -> combined.contains("supplier") ? 0.95 : 0.05;
            case "rel_depends_on" -> combined.contains("subject to") ? 0.9 : 0.05;
            case "rel_modifies" -> combined.contains("amend") || combined.contains("modify")
                    ? 0.9 : 0.05;
            case "rel_survives" -> combined.contains("survive") ? 0.9 : 0.05;
            case "rel_carves_out" -> combined.contains("carve") || combined.contains("do not apply")
                    ? 0.9 : 0.05;
            case "same_legal_issue", "same_clause_function", "comparable_context" -> 0.9;
            case "compatible_with_policy" -> text.toLowerCase().contains("uncapped")
                    || text.toLowerCase().contains("unlimited") ? 0.05 : 0.95;
            default -> 0.05;
        };
    }

    private String firstWord(String cue) {
        return cue.split(" ")[0];
    }

    private String stateText(Object state) {
        if (state instanceof Map<?, ?> payload) {
            for (var key : List.of("text", "excerpt", "precedentText", "policyTexts")) {
                if (payload.get(key) instanceof String value) {
                    return value;
                }
            }
            for (var key : List.of("source", "target")) {
                if (payload.get(key) instanceof Map<?, ?> side
                        && side.get("text") instanceof String value) {
                    return value;
                }
            }
        }
        return "";
    }

    private String headingOf(Object state) {
        if (state instanceof Map<?, ?> payload) {
            for (var key : List.of("heading", "title")) {
                if (payload.get(key) instanceof String heading) {
                    return heading;
                }
            }
        }
        return "";
    }
}
