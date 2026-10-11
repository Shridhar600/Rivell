package com.rivell.workbench.review;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.rivell.workbench.intelligence.ChoiceQuestion;
import com.rivell.workbench.intelligence.DecisionSpec;
import com.rivell.workbench.intelligence.QuestionPack;
import com.rivell.workbench.intelligence.SemanticQuestion;
import com.rivell.workbench.intelligence.SemanticState;

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

    public static ChoiceQuestion positionQuestion(String instructions) {
        return new ChoiceQuestion(instructions, OPTIONS);
    }

    public static Set<String> flaggingOptions() {
        return Set.copyOf(FLAGGING);
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

    /** The slice of the review a policy comparison needs. */
    public record PolicyComparisonState(
            int schemaVersion, String contractType, String ourSide, String heading, String clause)
            implements SemanticState {

        @Override
        public Object payload() {
            return this;
        }
    }
}
