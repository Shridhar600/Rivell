package com.rivell.workbench.review;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.rivell.workbench.intelligence.ReasoningSpec;
import com.rivell.workbench.intelligence.SemanticState;

/**
 * Engine v2 second opinion (spec §6 ADJUDICATE): one bounded generative call that judges one clause
 * against one playbook position after Jev was unsure. It may only answer with an option it is
 * offered (Jev's plausible ones) and must quote the clause words its answer rests on. Its answer is
 * input to code-owned routing, never a finding by itself.
 */
public final class AdjudicationSpecs {

    public static final String SECOND_OPINION_SPEC = "policy-comparison-second-opinion";
    public static final int SECOND_OPINION_VERSION = 1;
    public static final int STATE_SCHEMA = 1;
    public static final String CATEGORY = "category";
    public static final String QUOTE = "quote";
    public static final String REASON = "reason";

    private AdjudicationSpecs() {
    }

    /** {@code offered}: the option keys the answer must come from. */
    public static ReasoningSpec secondOpinion(Set<String> offered) {
        var fields = new LinkedHashMap<String, String>();
        fields.put(CATEGORY, "The one option key from options that best describes the clause "
                + "against the position.");
        fields.put(QUOTE, "The exact words from clause that your answer rests on, copied character "
                + "for character: one sentence or less.");
        fields.put(REASON, "One or two sentences for a lawyer on our side: why the quoted words put "
                + "the clause in that option.");
        return new ReasoningSpec(SECOND_OPINION_SPEC, SECOND_OPINION_VERSION,
                "Give a second opinion in a contract review. Judge the clause against our playbook "
                        + "position, for our side (ourSide), and pick exactly one of the given "
                        + "options. Read the clause as written: do not assume terms it does not "
                        + "contain. A clause can deal with the subject and still be fine for us.",
                STATE_SCHEMA, fields, Map.of(CATEGORY, Set.copyOf(offered)));
    }

    /**
     * {@code position}: the position as Jev saw it (preferred, fallbacks, walk-away).
     * {@code options}: each offered option key with the wording Jev saw.
     */
    public record SecondOpinionState(
            int schemaVersion, String ourSide, String contractType, String heading, String clause,
            String position, Map<String, String> options) implements SemanticState {

        @Override
        public Object payload() {
            return this;
        }
    }
}
