package com.rivell.workbench.platform.demo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.rivell.workbench.intelligence.ChoiceAnswer;
import com.rivell.workbench.intelligence.ChoiceQuestion;
import com.rivell.workbench.intelligence.DecisionExecutionRequest;
import com.rivell.workbench.intelligence.ProviderExecutionResult;
import com.rivell.workbench.intelligence.SemanticAnswer;
import com.rivell.workbench.intelligence.SemanticDecisionProvider;
import com.rivell.workbench.intelligence.TokenUsage;
import com.rivell.workbench.intelligence.YesNoAnswer;

/**
 * Deterministic decision provider for local demo data only.
 *
 * <p>Without a provider the analysis layer fails closed, so a review can only ever reach
 * COMPLETED or FAILED, never a truthful "finished, and here is what it found". This
 * provider answers every question from the question pack and the frozen clause text, with no
 * network call and no model, so the real review pipeline runs end to end offline and produces
 * real runs, real check executions and real findings.
 *
 * <p>Answers follow distinctive wording in the clause under review rather than returning a
 * constant. That matters for more than tidiness: concept classification feeds the review plan
 * builder, so a provider that answered "yes" to every concept would classify every clause as
 * the same one. The plan would then contain nothing but presence checks, every clause would
 * satisfy them through a secondary concept, and the review would complete having found
 * nothing. Cue-driven answers let the demo fixture's own wording decide which concept a clause
 * is, which is what lets the real domain produce real findings.
 *
 * <p>This is a fixture-shaped stand-in for a model, not a calibrated one. The classifications
 * it returns are exactly what the demo documents say, and the fail-closed bands may still
 * record a check as degraded. That is the honest outcome for an uncalibrated provider, and it
 * is why the demo never claims its findings are what a production model would conclude.
 *
 * <p>Registered only when demo seeding is explicitly enabled.
 */
public final class DemoScriptedDecisionProvider implements SemanticDecisionProvider {

    static final double PROBABILITY = 0.9;
    static final double CONFIDENT = 0.95;
    static final double ABSENT = 0.05;
    static final String RESOLVED_MODEL = "rivell-demo-scripted-1";

    @Override
    public String id() {
        return "demo-scripted";
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
        return new ProviderExecutionResult(answers, RESOLVED_MODEL, new TokenUsage(0, 0));
    }

    /**
     * Returns a full distribution rather than a single winning option. A one-entry map makes
     * every choice look uncontested, which would defeat the uncertainty bands the domain uses
     * to decide whether a classification is trustworthy.
     */
    private ChoiceAnswer choiceAnswer(String name, ChoiceQuestion choice, Object state) {
        if (choice.criteria().containsKey(
                com.rivell.workbench.review.PolicyComparisonSpecs.WALK_AWAY)) {
            return policyComparisonAnswer(state, choice);
        }
        var candidate = switch (name) {
            case "primary_topic" -> primaryConceptCue(state, choice);
            case "variant_role" -> variantRoleCue(state, choice);
            case "governing_law" -> cues(state).contains("england") ? "UK" : "US";
            default -> choice.criteria().keySet().iterator().next();
        };
        var selected = choice.criteria().containsKey(candidate)
                ? candidate
                : choice.criteria().keySet().iterator().next();
        var probabilities = new LinkedHashMap<String, Double>();
        choice.criteria().keySet().forEach(option ->
                probabilities.put(option, option.equals(selected) ? 0.92 : 0.04));
        return new ChoiceAnswer(selected, 0.92, probabilities);
    }

    /**
     * The demo fixtures predate the 90-topic taxonomy, so the expected main subject of an
     * ambiguous clause follows the core-concept order the old vocabulary used, not the
     * registry's arbitrary leaf order.
     */
    private static final List<String> CORE_ORDER = List.of(
            "CONFIDENTIALITY_OBLIGATIONS", "TERM", "LIMITATION_OF_LIABILITY",
            "INDEMNIFICATION", "GOVERNING_LAW");

    private String primaryConceptCue(Object state, ChoiceQuestion choice) {
        var heading = headingOf(state).toLowerCase();
        var text = cues(state).toLowerCase();
        var ordered = choice.criteria().keySet().stream()
                .sorted(java.util.Comparator.comparingInt(DemoScriptedDecisionProvider::coreRank))
                .toList();
        for (var option : ordered) {
            var cue = option.toLowerCase().replace('_', ' ');
            if (heading.contains(cue) || heading.contains(cue.split(" ")[0])) {
                return option;
            }
        }
        for (var option : ordered) {
            var cue = option.toLowerCase().replace('_', ' ');
            if (text.contains(cue) || text.contains(cue.split(" ")[0])) {
                return option;
            }
        }
        return ordered.get(0);
    }

    private static int coreRank(String key) {
        var index = CORE_ORDER.indexOf(key);
        return index < 0 ? CORE_ORDER.size() : index;
    }

    /**
     * Answers one position question from the clause under review and the position text the
     * question carries. A cap above every stated fallback is a needs-fallback deviation, a
     * cap inside a fallback is within-fallback, and uncapped exposure against a position
     * that prohibits it is a walk-away. Without this the demo review would report every
     * clause compliant whatever it says.
     */
    private ChoiceAnswer policyComparisonAnswer(Object state, ChoiceQuestion choice) {
        var selected = policyComparisonCategory(state, choice.instructions());
        var probabilities = new LinkedHashMap<String, Double>();
        choice.criteria().keySet().forEach(option ->
                probabilities.put(option, option.equals(selected) ? 0.92 : 0.016));
        return new ChoiceAnswer(selected, 0.92, probabilities);
    }

    private String policyComparisonCategory(Object state, String instructions) {
        var clause = comparisonClause(state);
        var position = instructions == null ? "" : instructions.toLowerCase();
        if ((clause.contains("uncapped") || clause.contains("unlimited"))
                && (position.contains("uncapped") || position.contains("unlimited")
                    || position.contains("never accept") || position.contains("not acceptable"))) {
            return com.rivell.workbench.review.PolicyComparisonSpecs.WALK_AWAY;
        }
        var clauseMultiple = number(clause);
        if (clauseMultiple == null) {
            return com.rivell.workbench.review.PolicyComparisonSpecs.MEETS_PREFERRED;
        }
        var preferred = number(labelValue(instructions, "Preferred:"));
        if (preferred == null && position.contains("fees paid")) {
            preferred = 1d;
        }
        var fallback = number(labelValue(instructions, "Fallback 1:"));
        if (preferred != null && clauseMultiple <= preferred) {
            return com.rivell.workbench.review.PolicyComparisonSpecs.MEETS_PREFERRED;
        }
        if (fallback != null && clauseMultiple <= fallback) {
            return com.rivell.workbench.review.PolicyComparisonSpecs.WITHIN_FALLBACK;
        }
        if (preferred != null) {
            return com.rivell.workbench.review.PolicyComparisonSpecs.BEYOND_FALLBACK;
        }
        return com.rivell.workbench.review.PolicyComparisonSpecs.MEETS_PREFERRED;
    }

    private String comparisonClause(Object state) {
        if (state instanceof com.rivell.workbench.review.PolicyComparisonSpecs
                .PolicyComparisonState comparison) {
            return comparison.clause() == null ? "" : comparison.clause().toLowerCase();
        }
        return "";
    }

    private static String labelValue(String instructions, String label) {
        if (instructions == null) {
            return "";
        }
        var index = instructions.indexOf(label);
        if (index < 0) {
            return "";
        }
        var rest = instructions.substring(index + label.length());
        var end = rest.indexOf('\n');
        return end < 0 ? rest : rest.substring(0, end);
    }

    private java.util.regex.Matcher matcher(String text, String pattern) {
        return java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(text == null ? "" : text);
    }

    private Double number(String text) {
        if (text == null) {
            return null;
        }
        var bare = text.trim();
        if (bare.matches("\\d+(?:\\.\\d+)?")) {
            return Double.valueOf(bare);
        }
        var digits = matcher(text, "(\\d+(?:\\.\\d+)?)\\s*times");
        if (digits.find()) {
            return Double.valueOf(digits.group(1));
        }
        var word = matcher(text, "\\b(one|two|three|four|five)\\b\\s*times");
        if (word.find()) {
            return switch (word.group(1).toLowerCase()) {
                case "one" -> 1d;
                case "two" -> 2d;
                case "three" -> 3d;
                case "four" -> 4d;
                default -> 5d;
            };
        }
        return null;
    }

    private String variantRoleCue(Object state, ChoiceQuestion choice) {
        var heading = headingOf(state).toLowerCase();
        if (heading.contains("fallback")) {
            return "FALLBACK";
        }
        if (heading.contains("prohibited")) {
            return "PROHIBITED";
        }
        if (heading.contains("preferred")) {
            return "PREFERRED";
        }
        return choice.criteria().keySet().iterator().next();
    }

    private double cueProbability(String questionName, Object state) {
        if (isConflictPair(state)) {
            return conflictRelationshipCue(questionName, state);
        }
        var combined = (headingOf(state) + " " + cues(state)).toLowerCase();
        return switch (questionName) {
            case "topic_limitation_of_liability" ->
                    combined.contains("liability") || combined.contains(" cap ") ? CONFIDENT
                            : ABSENT;
            case "topic_confidentiality_obligations" ->
                    combined.contains("confidential") ? CONFIDENT : ABSENT;
            case "topic_term" ->
                    combined.contains("terminat") || combined.contains(" term ")
                            ? CONFIDENT : ABSENT;
            case "topic_indemnification" ->
                    combined.contains("indemnif") ? CONFIDENT : ABSENT;
            case "topic_governing_law" -> combined.contains("govern") ? CONFIDENT : ABSENT;
            case "role_preferred" -> present(combined, "preferred");
            case "role_fallback" -> present(combined, "fallback");
            case "role_prohibited" -> present(combined, "prohibited");
            case "role_escalation" -> present(combined, "escalat");
            case "role_commentary" -> present(combined, "commentary");
            case "requires_approval" -> present(combined, "approval");
            case "narrows_scope" -> present(combined, "united states");
            case "source_supports" -> CONFIDENT;
            case "role_disclosing_party" -> present(combined, "disclosing");
            case "role_receiving_party" -> present(combined, "receiving");
            case "role_customer" -> present(combined, "customer");
            case "role_supplier" -> present(combined, "supplier");
            case "rel_depends_on" -> present(combined, "subject to");
            case "rel_modifies" ->
                    combined.contains("amend") || combined.contains("modify") ? PROBABILITY
                            : ABSENT;
            case "rel_survives" -> present(combined, "survive");
            case "rel_carves_out" ->
                    combined.contains("carve") || combined.contains("do not apply")
                            ? PROBABILITY : ABSENT;
            case "same_legal_issue", "same_clause_function", "comparable_context" -> PROBABILITY;
            case "compatible_with_policy" ->
                    combined.contains("uncapped") || combined.contains("unlimited") ? ABSENT
                            : CONFIDENT;
            default -> ABSENT;
        };
    }

    /**
     * Answers the knowledge conflict comparison from the two texts it is actually given.
     *
     * <p>Without this the provider had no access to the pair at all, so every comparison
     * returned "no" and a candidate could never be shown genuinely contradicting published
     * policy. The answers are still cue-driven rather than unconditional: a contradiction is
     * proposed only when one side states an uncapped exposure while the other states a
     * quantified limit, which is the clearest case of two positions that cannot both be
     * followed. Supersession intent is recognised only from explicit replacement language.
     */
    private static boolean isConflictPair(Object state) {
        return state instanceof Map<?, ?> pair
                && pair.get("new") instanceof Map<?, ?>
                && pair.get("published") instanceof Map<?, ?>;
    }

    private static String sideText(Object side) {
        if (side instanceof Map<?, ?> map && map.get("text") instanceof String text) {
            return text.toLowerCase();
        }
        return "";
    }

    private double conflictRelationshipCue(String questionName, Object pair) {
        var candidate = sideText(((Map<?, ?>) pair).get("new"));
        var published = sideText(((Map<?, ?>) pair).get("published"));
        return switch (questionName) {
            case "contradicts" -> {
                var candidateProposesUncapped = proposesUncappedExposure(candidate);
                var publishedProposesUncapped = proposesUncappedExposure(published);
                var candidateStatesLimit = statesLimit(candidate);
                var publishedStatesLimit = statesLimit(published);
                yield candidateProposesUncapped && publishedStatesLimit
                        || publishedProposesUncapped && candidateStatesLimit
                        ? PROBABILITY : ABSENT;
            }
            case "supersedes" ->
                    REPLACEMENT_LANGUAGE.matcher(candidate).find() ? PROBABILITY : ABSENT;
            default -> ABSENT;
        };
    }

    /**
     * True only when the text actually <em>proposes</em> uncapped exposure. A playbook line that
     * prohibits uncapped liability mentions the same words while agreeing with a capped clause, so
     * treating any mention as a proposal invents a conflict that does not exist.
     */
    private static boolean proposesUncappedExposure(String text) {
        var mentionsUncapped = text.contains("uncapped") || text.contains("unlimited")
                || text.contains("no cap") || text.contains("without limit");
        return mentionsUncapped && !rejectsUncapped(text);
    }

    private static boolean rejectsUncapped(String text) {
        return text.contains("prohibited") || text.contains("not acceptable")
                || text.contains("never acceptable") || text.contains("reject")
                || text.contains("avoid") || text.contains("must be capped");
    }

    private static boolean statesLimit(String text) {
        return text.contains("shall not exceed") || text.contains("should not exceed")
                || text.contains("capped at") || text.contains("maximum of")
                || COMPILED_MULTIPLE.matcher(text).find();
    }

    private static final java.util.regex.Pattern COMPILED_MULTIPLE =
            java.util.regex.Pattern.compile(
                    "(\\d+(?:\\.\\d+)?|one|two|three|four|five)\\s+times",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    private static final java.util.regex.Pattern REPLACEMENT_LANGUAGE =
            java.util.regex.Pattern.compile(
                    "\\b(replace|supersede|supersedes|no longer apply|withdrawn|updated policy)\\b",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    private double present(String combined, String cue) {
        return combined.contains(cue) ? PROBABILITY : 0.02;
    }

    private String cues(Object state) {
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
