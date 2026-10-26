package com.rivell.workbench.review.api;

import com.rivell.workbench.review.CheckExpectation;

/**
 * Frozen coverage state of one declared concept: a concept that receives
 * completeness credit from a secondary-only classification gets no substantive check,
 * so the review cannot silently look fully assessed.
 */
public record ReviewCoverageView(
        String concept,
        CheckExpectation expectation,
        Coverage coverage) {

    public enum Coverage {
        PRESENT_PRIMARY,
        PRESENT_SECONDARY_ONLY,
        ABSENT,
        UNKNOWN
    }
}
