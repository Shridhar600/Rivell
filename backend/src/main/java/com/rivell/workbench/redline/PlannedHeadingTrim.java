package com.rivell.workbench.redline;

import java.util.Objects;

/**
 * Clause text that shares its paragraph with the clause heading ("5. Term. This Agreement…").
 * The paragraph at the anchor must read exactly {@code expectedText}; everything after
 * {@code keptHeading} is tracked-deleted and the heading itself stays.
 */
public record PlannedHeadingTrim(
        int bodyIndex, int paragraphIndex, String expectedText, String keptHeading) {

    public PlannedHeadingTrim {
        if (bodyIndex < 0 || paragraphIndex < 0) {
            throw new IllegalArgumentException("anchor indices must be >= 0");
        }
        Objects.requireNonNull(expectedText, "expectedText");
        Objects.requireNonNull(keptHeading, "keptHeading");
        if (keptHeading.isBlank() || !expectedText.startsWith(keptHeading)
                || expectedText.length() == keptHeading.length()) {
            throw new IllegalArgumentException(
                    "the kept heading must be a strict prefix of the paragraph text");
        }
    }
}
