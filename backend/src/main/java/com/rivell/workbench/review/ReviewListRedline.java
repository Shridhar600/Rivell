package com.rivell.workbench.review;

import java.time.Instant;

/**
 * Redline state of one review on a list page, absent when no redline was ever requested.
 *
 * <p>Absence, a completed run with nothing to redline, a verified artifact and a terminal
 * failure are four different facts and are kept distinguishable here.
 *
 * <p>{@code verifiedChangeCount} is present only for a completed execution that really
 * produced a redline. A failed execution may have internally verified some changes, so
 * its count is withheld rather than reported as a partial success; an execution with
 * nothing to redline verified nothing, so it carries no count either.
 */
public record ReviewListRedline(
        String status,
        String outcome,
        Integer verifiedChangeCount,
        boolean downloadAvailable,
        Instant completedAt) {
}
