package com.rivell.workbench.review;

/**
 * The human-declared review context a review was created against, read from the frozen
 * snapshot rather than from live contract state.
 *
 * <p>Values are stored exactly as declared and reported under their own domain names.
 * {@code ourSideRole} and {@code paperSource} are genuinely nullable because a review
 * may be created from a minimal profile, and an undeclared jurisdiction is the real
 * {@code UNSPECIFIED} constant rather than a missing value. Nothing here is inferred,
 * defaulted or reinterpreted.
 */
public record ReviewContextSummary(
        String contractType,
        String ourSideRole,
        String paperSource,
        String jurisdiction) {
}
