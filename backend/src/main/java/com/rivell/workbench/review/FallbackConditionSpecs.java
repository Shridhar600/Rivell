package com.rivell.workbench.review;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.rivell.workbench.intelligence.ReasoningSpec;
import com.rivell.workbench.intelligence.SemanticState;

/**
 * Severity R3 lever P3: one bounded generative call that checks a clause, already judged "within a
 * listed fallback", against the conditions of that fallback one by one. A fallback is acceptable only
 * when all its conditions hold, and a condition can live outside the clause (who the parties are, the
 * governing law). For every condition the call answers MET, UNMET or UNKNOWN and quotes the words it
 * rests on, from the clause or from the contract facts it is given. Its answer is input to code-owned
 * rules (an UNMET with a quote the clause or the facts hold, in every listed fallback, makes the
 * position BEYOND_FALLBACK; nothing else changes it), never a finding by itself.
 */
public final class FallbackConditionSpecs {

    public static final String SPEC_ID = "policy-fallback-conditions";
    public static final int SPEC_VERSION = 1;
    public static final int STATE_SCHEMA = 1;
    public static final String FALLBACK_LISTED = "fallbackListed";
    public static final String CONDITIONS = "conditions";
    public static final String YES = "YES";
    public static final String NO = "NO";
    public static final String MET = "MET";
    public static final String UNMET = "UNMET";
    public static final String UNKNOWN = "UNKNOWN";

    private FallbackConditionSpecs() {
    }

    public static ReasoningSpec spec() {
        var fields = new LinkedHashMap<String, String>();
        fields.put(FALLBACK_LISTED, "YES when the position text lists at least one fallback (an "
                + "acceptable second position), NO when it lists only a preferred position and/or a "
                + "walk-away.");
        fields.put(CONDITIONS, "One line per condition, each 'fallback number | MET or UNMET or UNKNOWN "
                + "| exact words you rely on, copied character for character from clause or facts, or "
                + "- when UNKNOWN | the condition in at most 15 words'. Number the listed fallbacks 1, 2, "
                + "... in the order of the position text. When no fallback is listed, write exactly one "
                + "line numbered 1 for the condition 'the clause meets the preferred position'.");
        return new ReasoningSpec(SPEC_ID, SPEC_VERSION,
                "Check a contract clause against the conditions of our playbook fallback, for our side "
                        + "(ourSide). The position lists a preferred position, fallbacks and a walk-away. "
                        + "A fallback is acceptable only when ALL of its conditions are met. Split each "
                        + "listed fallback into its separate conditions (a number, a time, a scope, an "
                        + "'if' or 'where' clause, a limit on who or where) and check each one against "
                        + "the clause and against the contract facts. Answer MET only when you can quote "
                        + "words that meet the condition. Answer UNMET only when you can quote words "
                        + "that fail it: for example the clause words that set a worse number, or the "
                        + "fact that shows the condition's party or place is not the one named. Answer "
                        + "UNKNOWN when the clause and the facts do not show it: never guess and never "
                        + "assume terms the clause does not contain. Read the clause as written.",
                STATE_SCHEMA, fields, Map.of(FALLBACK_LISTED, Set.of(YES, NO)));
    }

    /**
     * {@code position}: the position as Jev saw it (preferred, fallbacks, walk-away).
     * {@code facts}: what the contract says about itself outside the clause: our side, the parties
     * with their roles, the governing law; each line can be quoted.
     */
    public record FallbackConditionState(
            int schemaVersion, String ourSide, String contractType, String heading, String clause,
            String position, List<String> facts) implements SemanticState {

        public FallbackConditionState {
            facts = facts == null ? List.of() : List.copyOf(facts);
        }

        @Override
        public Object payload() {
            return this;
        }
    }
}
