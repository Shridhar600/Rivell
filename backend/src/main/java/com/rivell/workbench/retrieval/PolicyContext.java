package com.rivell.workbench.retrieval;

import java.util.List;

/**
 * Applicable published policy relative to which precedent compatibility is assessed
 * (AD #8.3). Compatibility is never assessed against missing or ambiguous policy —
 * that is an honest NOT_ASSESSED state.
 */
public record PolicyContext(boolean policyResolved, List<String> policyTexts) {

    public static PolicyContext unassessed() {
        return new PolicyContext(false, List.of());
    }
}
