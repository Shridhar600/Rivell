package com.rivell.workbench.review.api;

import com.rivell.workbench.contracts.LegalConcept;
import com.rivell.workbench.review.CheckExpectation;

/**
 * Frozen coverage state of one declared concept (DW-024): a concept that receives
 * completeness credit from a secondary-only classification gets no substantive check,
 * so the review cannot silently look fully assessed.
 */
public record ReviewCoverageView(
        LegalConcept concept,
        CheckExpectation expectation,
        Coverage coverage) {

    public enum Coverage {
        PRESENT_PRIMARY,
        PRESENT_SECONDARY_ONLY,
        ABSENT
    }
}
