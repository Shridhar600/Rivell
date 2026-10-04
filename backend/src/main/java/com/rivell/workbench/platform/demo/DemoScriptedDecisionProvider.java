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
import com.rivell.workbench.review.M7DecisionSpecs;

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
        var candidate = switch (name) {
            case "primary_concept" -> primaryConceptCue(state, choice);
            case "variant_role" -> variantRoleCue(state, choice);
            case "governing_law" -> cues(state).contains("england") ? "UK" : "US";
            case "deviation_type" -> liabilityDeviation(state, choice);
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

    private String primaryConceptCue(Object state, ChoiceQuestion choice) {
        var heading = headingOf(state).toLowerCase();
        var text = cues(state);
        for (var option : choice.criteria().keySet()) {
            var cue = option.toLowerCase().replace('_', ' ');
            if (heading.contains(cue) || heading.contains(cue.split(" ")[0])) {
                return option;
            }
        }
        for (var option : choice.criteria().keySet()) {
            if (text.contains(option.toLowerCase().replace('_', ' '))) {
                return option;
            }
        }
        return choice.criteria().keySet().iterator().next();
    }

    /**
     * Compares the clause's own extracted cap against the multiple the published policy
     * states, using the two values the domain actually supplies: the typed cap multiple from
     * the frozen clause and the authored policy text.
     *
     * <p>Answering this from the data rather than defaulting to the first option is the
     * difference between a demo review that finds a real deviation and one that reports the
     * contract as compliant whatever it says. A clause with no numeric cap is a prohibited
     * structure; a cap above the policy's stated multiple is above the preferred position;
     * anything at or below it is no deviation.
     */
    private String liabilityDeviation(Object state, ChoiceQuestion choice) {
        var clause = field(state, "clauseText").toLowerCase();
        var policy = field(state, "preferredPosition");
        var cap = field(state, "capMultiple");
        if (clause.contains("uncapped") || clause.contains("unlimited")) {
            return "PROHIBITED_STRUCTURE";
        }
        var capValue = number(cap);
        var policyValue = number(policy);
        if (capValue == null || policyValue == null) {
            return "NOT_COMPARABLE";
        }
        return capValue.doubleValue() > policyValue
                ? "CAP_ABOVE_PREFERRED" : "NO_DEVIATION";
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

    private String field(Object state, String name) {
        if (state instanceof M7DecisionSpecs.LiabilityDeviationState deviation) {
            return switch (name) {
                case "clauseText" -> deviation.clauseText();
                case "capMultiple" -> deviation.capMultiple();
                case "preferredPosition" -> deviation.preferredPosition();
                default -> "";
            };
        }
        return "";
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
        var combined = (headingOf(state) + " " + cues(state)).toLowerCase();
        return switch (questionName) {
            case "concept_limitation_of_liability" ->
                    combined.contains("liability") || combined.contains(" cap ") ? CONFIDENT
                            : ABSENT;
            case "concept_confidentiality" ->
                    combined.contains("confidential") ? CONFIDENT : ABSENT;
            case "concept_term_termination" ->
                    combined.contains("terminat") || combined.contains(" term ")
                            ? CONFIDENT : ABSENT;
            case "concept_indemnification" ->
                    combined.contains("indemnif") ? CONFIDENT : ABSENT;
            case "concept_governing_law" -> combined.contains("govern") ? CONFIDENT : ABSENT;
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