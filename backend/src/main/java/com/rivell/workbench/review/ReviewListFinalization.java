package com.rivell.workbench.review;

import java.time.Instant;

/**
 * Finalization state of one review on a list page.
 *
 * <p>A review is finalized exactly when a frozen change set exists for it. Finalization
 * is a separate fact from analysis completion: a completed analysis has not been
 * finalized, and this record is absent until it has.
 *
 * <p>{@code approvedChanges} counts the frozen change set itself, not the findings that
 * led to it, so a rejected finding contributes nothing and an accepted one contributes
 * exactly one executable change.
 */
public record ReviewListFinalization(Instant finalizedAt, int approvedChanges) {
}
