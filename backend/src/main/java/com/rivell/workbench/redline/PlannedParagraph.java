package com.rivell.workbench.redline;

import java.util.Objects;

/**
 * A paragraph anchor resolved from the frozen M1 structure: the element at
 * {@code bodyIndex} must be the paragraph with document-order ordinal
 * {@code paragraphIndex} and text exactly equal to {@code expectedText}.
 */
public record PlannedParagraph(int bodyIndex, int paragraphIndex, String expectedText) {

    public PlannedParagraph {
        if (bodyIndex < 0 || paragraphIndex < 0) {
            throw new IllegalArgumentException("anchor indices must be >= 0");
        }
        Objects.requireNonNull(expectedText, "expectedText");
    }
}
