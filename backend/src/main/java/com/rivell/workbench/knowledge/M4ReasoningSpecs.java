package com.rivell.workbench.knowledge;

import java.util.Map;

/**
 * M4 generative reasoning specs: bounded open-ended extraction, versioned like
 * decision specs. Only fields that cannot be expressed as bounded judgments or
 * deterministic extraction qualify.
 */
final class M4ReasoningSpecs {

    static final String ESCALATION_TRIGGER_SPEC = "escalation-trigger-extraction";

    private M4ReasoningSpecs() {
    }

    static com.rivell.workbench.intelligence.ReasoningSpec escalationTrigger() {
        return new com.rivell.workbench.intelligence.ReasoningSpec(
                ESCALATION_TRIGGER_SPEC, 1,
                "Extract the escalation trigger condition stated in playbook guidance.",
                M4SemanticStates.PlaybookUnitState.SCHEMA_VERSION,
                Map.of("escalationTrigger",
                        "The escalation trigger condition stated in the guidance, in the "
                                + "source's own words. Extract only what is stated; never invent conditions."));
    }
}
