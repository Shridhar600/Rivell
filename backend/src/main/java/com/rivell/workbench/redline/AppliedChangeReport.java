package com.rivell.workbench.redline;

import java.util.Objects;
import java.util.UUID;

/** Per-change render evidence: applied paragraph counts and allocated revision ids. */
public record AppliedChangeReport(
        UUID approvedChangeId,
        int ordinal,
        int paragraphsDeleted,
        int paragraphsInserted,
        long revisionIdStart,
        long revisionIdEnd) {

    public AppliedChangeReport {
        Objects.requireNonNull(approvedChangeId, "approvedChangeId");
        if (ordinal < 0 || paragraphsDeleted < 0 || paragraphsInserted < 0) {
            throw new IllegalArgumentException("counts must be >= 0");
        }
        if (revisionIdStart < 0 || revisionIdEnd < revisionIdStart) {
            throw new IllegalArgumentException("revision id range must be ascending");
        }
    }
}
