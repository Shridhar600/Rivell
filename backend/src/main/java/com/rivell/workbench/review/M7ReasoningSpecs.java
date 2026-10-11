package com.rivell.workbench.review;

import java.util.Map;

import com.rivell.workbench.intelligence.ReasoningSpec;
import com.rivell.workbench.intelligence.SemanticState;

/** M7 generative reasoning specs: open-ended drafting only where no approved path exists. */
public final class M7ReasoningSpecs {

    public static final String OPEN_ENDED_POSITION_SPEC = "m7-open-ended-position";
    public static final int OPEN_ENDED_POSITION_VERSION = 1;
    public static final int OPEN_ENDED_STATE_SCHEMA = 1;
    public static final String PROPOSED_LANGUAGE = "proposedLanguage";
    public static final String EXPLANATION = "explanation";

    private M7ReasoningSpecs() {
    }

    /**
     * Propose candidate clause language against one playbook position the clause deviates
     * from (walk-away or beyond every fallback) when no approved drafting exists. The
     * state is the clause and the position text; the result is machine language, never
     * approved wording.
     */
    public static ReasoningSpec openEndedPosition() {
        return new ReasoningSpec(
                OPEN_ENDED_POSITION_SPEC,
                OPEN_ENDED_POSITION_VERSION,
                "Propose candidate clause language for a clause that deviates from a "
                        + "published playbook position and for which no approved drafting exists.",
                OPEN_ENDED_STATE_SCHEMA,
                Map.of(
                        PROPOSED_LANGUAGE,
                        "Candidate replacement clause language grounded only in the given "
                                + "clause and position text. Never present it as approved wording.",
                        EXPLANATION,
                        "Short explanation of why the candidate language addresses the "
                                + "position, grounded in the given evidence."));
    }

    public record OpenEndedPositionState(
            int schemaVersion, String clauseText, String positionText) implements SemanticState {

        @Override
        public Object payload() {
            return this;
        }
    }
}
