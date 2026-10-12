package com.rivell.workbench.contracts;

import java.util.Map;
import java.util.Set;

import com.rivell.workbench.intelligence.ReasoningSpec;

/**
 * M5 generative reasoning specs: open-ended extraction that deterministic parsing
 * and bounded judgments cannot produce. Strict typed fields, code-validated, and
 * independently verified before use.
 */
final class M5ReasoningSpecs {

    static final String LIABILITY_CAP_SPEC = "liability-cap-extraction";
    static final String TERMINATION_NOTICE_SPEC = "termination-notice-extraction";

    private M5ReasoningSpecs() {
    }

    /** The value a generative field holds when the clause does not state it. */
    static final String NOT_STATED = "NONE";

    /**
     * v2: a clause with a fixed-sum cap, no cap, or no multiple answers {@link #NOT_STATED};
     * v1 had no such value, so the model returned a blank field and the call failed.
     */
    static ReasoningSpec liabilityCap() {
        return new ReasoningSpec(
                LIABILITY_CAP_SPEC, 2,
                "Extract the liability cap multiple and its basis from a limitation-of-"
                        + "liability clause. Extract only what the clause states; never invent "
                        + "values.",
                M5SemanticStates.ClauseState.SCHEMA_VERSION,
                Map.of(
                        "liabilityCapMultiple",
                        "The numeric multiplier of the liability cap stated in the clause "
                                + "(for example '3' for 'three times the fees', '1' for 'the fees "
                                + "paid in the preceding 12 months'). Numbers only. NONE when the "
                                + "cap is a fixed sum, the liability is uncapped, or the clause "
                                + "states no cap.",
                        "capBasis",
                        "The basis of the cap: PRECEDING_12_MONTH_FEES, ANNUAL_FEES, or "
                                + "UNSPECIFIED."),
                Map.of("capBasis", Set.of("PRECEDING_12_MONTH_FEES", "ANNUAL_FEES", "UNSPECIFIED")));
    }

    static ReasoningSpec terminationNotice() {
        return new ReasoningSpec(
                TERMINATION_NOTICE_SPEC, 2,
                "Extract the notice period in days for termination for convenience. "
                        + "Extract only what the clause states; never invent values.",
                M5SemanticStates.ClauseState.SCHEMA_VERSION,
                Map.of(
                        "noticePeriodDays",
                        "The termination-for-convenience notice period in days as stated "
                                + "in the clause (for example '60'). Numbers only. NONE when the "
                                + "clause states no such period."));
    }
}
