package com.rivell.workbench.contracts;

import java.util.Map;

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

    static ReasoningSpec liabilityCap() {
        return new ReasoningSpec(
                LIABILITY_CAP_SPEC, 1,
                "Extract the liability cap multiple and its basis from a limitation-of-"
                        + "liability clause. Extract only what the clause states; never invent "
                        + "values.",
                M5SemanticStates.ClauseState.SCHEMA_VERSION,
                Map.of(
                        "liabilityCapMultiple",
                        "The numeric multiplier of the liability cap stated in the clause "
                                + "(for example '3' for 'three times the fees'). Numbers only.",
                        "capBasis",
                        "The basis of the cap: PRECEDING_12_MONTH_FEES, ANNUAL_FEES, or "
                                + "UNSPECIFIED."));
    }

    static ReasoningSpec terminationNotice() {
        return new ReasoningSpec(
                TERMINATION_NOTICE_SPEC, 1,
                "Extract the notice period in days for termination for convenience. "
                        + "Extract only what the clause states; never invent values.",
                M5SemanticStates.ClauseState.SCHEMA_VERSION,
                Map.of(
                        "noticePeriodDays",
                        "The termination-for-convenience notice period in days as stated "
                                + "in the clause (for example '60'). Numbers only."));
    }
}
