package com.rivell.workbench.review;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rivell.workbench.intelligence.ChoiceQuestion;
import com.rivell.workbench.intelligence.DecisionSpec;
import com.rivell.workbench.intelligence.QuestionPack;
import com.rivell.workbench.intelligence.SemanticQuestion;
import com.rivell.workbench.intelligence.SemanticState;
import com.rivell.workbench.intelligence.YesNoQuestion;

/**
 * The {@code policy-comparison} decision (design §6a): one clause against every
 * published position that shares a topic with it, one Choice per position. The option
 * wording is the measured wording from the deviation probe; the question pack is
 * dynamic (one {@code position_N} question per resolved position) so this class owns
 * only the spec identity, state and criteria.
 */
public final class PolicyComparisonSpecs {

    public static final String POLICY_COMPARISON_SPEC = "policy-comparison";
    public static final int POLICY_COMPARISON_VERSION = 1;
    /** The same comparison asked again for measurement (engine v2 P0); never a finding. */
    public static final String ORDER_PROBE_SPEC = "policy-comparison-order-probe";
    public static final int STATE_SCHEMA_VERSION = 1;

    public static final String MEETS_PREFERRED = "MEETS_PREFERRED";
    public static final String WITHIN_FALLBACK = "WITHIN_FALLBACK";
    public static final String BEYOND_FALLBACK = "BEYOND_FALLBACK";
    public static final String WALK_AWAY = "WALK_AWAY";
    public static final String UNCLEAR = "UNCLEAR";
    public static final String NOT_ADDRESSED = "NOT_ADDRESSED";

    /** Options and wording verbatim from {@code jev_deviation_probe.py} OPTIONS. */
    public static final Map<String, String> OPTIONS = options();

    /**
     * The four options that can flag a position, in priority order for a tie on the
     * code-owned argmax: the more severe category wins.
     */
    public static final java.util.List<String> FLAGGING = java.util.List.of(
            WALK_AWAY, BEYOND_FALLBACK, UNCLEAR, WITHIN_FALLBACK);

    private PolicyComparisonSpecs() {
    }

    public static DecisionSpec spec(Map<String, SemanticQuestion> questions) {
        return new DecisionSpec(
                POLICY_COMPARISON_SPEC,
                POLICY_COMPARISON_VERSION,
                "Judge one contract clause against each published playbook position it "
                        + "shares a topic with; every position is judged independently.",
                STATE_SCHEMA_VERSION,
                new QuestionPack(questions));
    }

    public static DecisionSpec orderProbe(Map<String, SemanticQuestion> questions) {
        return new DecisionSpec(
                ORDER_PROBE_SPEC,
                POLICY_COMPARISON_VERSION,
                "Measurement only: the policy comparison asked again, options reversed or as "
                        + "asked; the answer never becomes a finding.",
                STATE_SCHEMA_VERSION,
                new QuestionPack(questions));
    }

    public static ChoiceQuestion positionQuestion(String instructions) {
        return positionQuestion(instructions, false);
    }

    /**
     * {@code reversed}: the same options and wording, last first. Jev "leans toward the option
     * that comes first" (jev-1.13 jaggedness notes); the probe measures how much.
     */
    public static ChoiceQuestion positionQuestion(String instructions, boolean reversed) {
        if (!reversed) {
            return new ChoiceQuestion(instructions, OPTIONS);
        }
        var keys = new java.util.ArrayList<>(OPTIONS.keySet());
        java.util.Collections.reverse(keys);
        var options = new LinkedHashMap<String, String>();
        keys.forEach(key -> options.put(key, OPTIONS.get(key)));
        return new ChoiceQuestion(instructions, options);
    }

    private static Map<String, String> options() {
        var options = new LinkedHashMap<String, String>();
        options.put(MEETS_PREFERRED, "The clause is consistent with our preferred position.");
        options.put(WITHIN_FALLBACK, "The clause departs from our preferred position but stays within one of "
                + "the listed fallbacks.");
        options.put(BEYOND_FALLBACK, "The clause is worse for us than every listed fallback, but does not hit "
                + "the walk-away.");
        options.put(WALK_AWAY, "The clause contains or permits what the walk-away says we must never accept.");
        options.put(UNCLEAR, "The clause deals with this subject, but its wording is ambiguous or depends on "
                + "something outside the contract, so compliance cannot be decided.");
        options.put(NOT_ADDRESSED, "The clause does not deal with the subject of this position, or the position "
                + "is written for a different kind of contract.");
        return java.util.Collections.unmodifiableMap(options);
    }

    public static final String UNCOVERED_CLAUSE_SPEC = "uncovered-clause";
    public static final int UNCOVERED_CLAUSE_VERSION = 2;
    public static final String NEEDS_REVIEW = "needs_review";

    /**
     * Triage for a clause no published position addresses (measured 2026-10-11 with
     * {@code jev_uncovered_probe.py} v2): is it unusual for this kind of contract and against
     * our side? Same state as the comparison. v2 names a limited remedy as adverse (dev2:
     * "the Discloser's only remedy is damages" read as normal for the discloser).
     */
    public static DecisionSpec uncoveredClause() {
        return new DecisionSpec(
                UNCOVERED_CLAUSE_SPEC,
                UNCOVERED_CLAUSE_VERSION,
                "Triage a contract clause that no playbook position addresses.",
                STATE_SCHEMA_VERSION,
                new QuestionPack(Map.of(NEEDS_REVIEW, new YesNoQuestion(
                        "Our playbook has no position on this clause. Is this clause unusual for "
                                + "this kind of contract and against our side, so that a lawyer "
                                + "must review it before signing?",
                        "It restricts our freedom to deal with others (exclusivity, "
                                + "non-solicitation, standstill, most-favoured pricing), imposes a "
                                + "penalty or fixed damages on us, grants the other side rights over "
                                + "our data, name or materials, takes away or limits a remedy we "
                                + "would normally have (an injunction, termination, a claim), or "
                                + "adds another one-sided obligation, cost or risk for us that this "
                                + "kind of contract does not normally contain.",
                        "The clause is normal for this kind of contract: standard mechanics, "
                                + "boilerplate, ordinary mutual obligations, or terms that protect "
                                + "us."))));
    }

    /**
     * The slice of the review a policy comparison needs. {@code definitions}: the contract's own
     * definitions of terms the clause uses (P3), read by the engine v2 judge; empty when the
     * property is off, and then never serialized (the state's schema version covers both shapes).
     */
    public record PolicyComparisonState(
            int schemaVersion, String contractType, String ourSide, String heading, String clause,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> definitions)
            implements SemanticState {

        public PolicyComparisonState {
            definitions = definitions == null ? List.of() : List.copyOf(definitions);
        }

        @Override
        public Object payload() {
            return this;
        }
    }
}
