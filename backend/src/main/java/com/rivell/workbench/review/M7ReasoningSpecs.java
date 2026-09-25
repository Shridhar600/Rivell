package com.rivell.workbench.review;

import java.util.Map;

import com.rivell.workbench.intelligence.ReasoningSpec;
import com.rivell.workbench.intelligence.SemanticState;

/** M7 generative reasoning specs: open-ended drafting only where no approved path exists. */
public final class M7ReasoningSpecs {

    public static final String OPEN_ENDED_LIABILITY_SPEC = "m7-open-ended-liability";
    public static final int OPEN_ENDED_LIABILITY_VERSION = 1;
    public static final int OPEN_ENDED_STATE_SCHEMA = 1;
    public static final String PROPOSED_LANGUAGE = "proposedLanguage";
    public static final String EXPLANATION = "explanation";

    private M7ReasoningSpecs() {
    }

    public static ReasoningSpec openEndedLiability() {
        return new ReasoningSpec(
                OPEN_ENDED_LIABILITY_SPEC,
                OPEN_ENDED_LIABILITY_VERSION,
                "Propose candidate limitation-of-liability language for a clause whose "
                        + "structure cannot be compared with policy and for which no "
                        + "approved drafting exists.",
                OPEN_ENDED_STATE_SCHEMA,
                Map.of(
                        PROPOSED_LANGUAGE,
                        "Candidate replacement clause language grounded only in the given "
                                + "clause and policy text. Never present it as approved wording.",
                        EXPLANATION,
                        "Short explanation of why the candidate language addresses the "
                                + "policy expectation, grounded in the given evidence."));
    }

    public record OpenEndedLiabilityState(
            int schemaVersion, String clauseText, String policyText) implements SemanticState {

        @Override
        public Object payload() {
            return this;
        }
    }
}
