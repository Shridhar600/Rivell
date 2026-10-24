package com.rivell.workbench.review;

import java.util.List;
import java.util.Map;

import com.rivell.workbench.intelligence.ReasoningSpec;
import com.rivell.workbench.intelligence.SemanticState;

/**
 * Whole-contract issue spotting (step-6 hybrid, opt-in): an LLM reads every clause with every
 * applicable playbook position and names the clauses a lawyer on our side must act on. It only
 * points: a spotted clause that no position check flagged becomes a "needs human review"
 * finding; the model's words never become contract text or a verdict on a position.
 */
public final class IssueSpottingSpecs {

    public static final String SPEC_ID = "contract-issue-spotting";
    /** Version 2 asks for one issue per line with a verbatim quote; version 1 named clauses only. */
    public static final int VERSION = 2;
    public static final int STATE_SCHEMA = 1;
    public static final String ISSUES = "issues";
    public static final String NONE = "NONE";

    private IssueSpottingSpecs() {
    }

    public static ReasoningSpec spec() {
        return new ReasoningSpec(SPEC_ID, VERSION,
                "Review the whole contract for our side (ourSide) against our playbook positions. "
                        + "Name each issue a lawyer on our side must act on before signing: it "
                        + "breaches or departs from a position, is ambiguous, conflicts with another "
                        + "clause or schedule, or is unusual and against us although no position "
                        + "covers it. Do not name clauses that meet our positions or are standard "
                        + "terms. Use the clause numbers given in the input, one issue per line, and "
                        + "quote the clause words that show the issue exactly.",
                STATE_SCHEMA,
                Map.of(ISSUES, "One line per issue (a clause may have more than one), at most 20 "
                        + "lines, each 'clause number | HIGH or MEDIUM | quote from the clause, at "
                        + "most 20 words | reason in at most 15 words'. Write NONE when no clause "
                        + "needs action."));
    }

    /** One clause as the model sees it: its number in the input, heading and text. */
    public record SpottingClause(int number, String heading, String text) {
    }

    public record SpottingState(
            int schemaVersion, String contractType, String ourSide, List<String> positions,
            List<SpottingClause> clauses) implements SemanticState {

        @Override
        public Object payload() {
            return this;
        }
    }
}
