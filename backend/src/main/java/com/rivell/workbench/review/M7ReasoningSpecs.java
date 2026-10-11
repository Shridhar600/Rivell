package com.rivell.workbench.review;

import java.util.Map;

import com.rivell.workbench.intelligence.ReasoningSpec;
import com.rivell.workbench.intelligence.SemanticState;

/** M7 generative reasoning specs: open-ended drafting only where no approved path exists. */
public final class M7ReasoningSpecs {

    public static final String OPEN_ENDED_POSITION_SPEC = "m7-open-ended-position";
    public static final int OPEN_ENDED_POSITION_VERSION = 2;
    public static final int OPEN_ENDED_STATE_SCHEMA = 2;
    public static final String PROPOSED_LANGUAGE = "proposedLanguage";
    public static final String EXPLANATION = "explanation";

    private M7ReasoningSpecs() {
    }

    /**
     * Propose new wording for one clause that deviates from a playbook position (walk-away or
     * beyond every fallback) when no approved drafting exists. The wording replaces the clause
     * body in a tracked change, so it must be contract text only, keep everything the position
     * does not touch, and be written for our side. The result is machine language, never
     * approved wording.
     */
    public static ReasoningSpec openEndedPosition() {
        return new ReasoningSpec(
                OPEN_ENDED_POSITION_SPEC,
                OPEN_ENDED_POSITION_VERSION,
                "Redraft one contract clause so that it meets our playbook position. Write the new "
                        + "text of the clause body as it should read in the signed contract, for our "
                        + "side (ourSide). The heading stays in the document: do not repeat it. "
                        + "Change only what the position needs; copy every other sentence and "
                        + "sub-clause of clauseText word for word, with its numbering. Aim for the "
                        + "preferred position; where the clause is closer to a listed fallback, "
                        + "meeting that fallback is enough. Never write what the walk-away forbids. "
                        + "Never remove a right, remedy or protection our side has in the clause, "
                        + "and never add an obligation on our side that the position does not ask "
                        + "for. Use the contract's own defined terms and party names. Write "
                        + "contract text only: no instructions such as 'Replace' or 'Delete', no "
                        + "notes, and no labels such as 'Candidate language'. To remove a sentence "
                        + "or sub-clause, leave it out.",
                OPEN_ENDED_STATE_SCHEMA,
                Map.of(
                        PROPOSED_LANGUAGE,
                        "The full new text of the clause body, without the heading: contract "
                                + "wording only, one sub-clause per line.",
                        EXPLANATION,
                        "Short explanation of what changed and which part of the position it "
                                + "meets, grounded in the given clause and position."));
    }

    /** {@code ourSide} is a sentence (see {@link OurSide}); {@code clauseText} is the body only. */
    public record OpenEndedPositionState(
            int schemaVersion, String ourSide, String contractType, String heading,
            String clauseText, String positionText) implements SemanticState {

        @Override
        public Object payload() {
            return this;
        }
    }
}
