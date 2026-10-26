package com.rivell.workbench.knowledge;

/**
 * A lower-level rule that names a different approver for the same stance than the winning rule.
 * Conflicts are shown, never hidden, so the person can pick either.
 */
public record AuthorityConflict(String approver, AuthorityLevel source, PositionRole stance) {

    public AuthorityConflict {
        if (approver == null || approver.isBlank()) {
            throw new IllegalArgumentException("An authority conflict needs an approver");
        }
        approver = approver.strip();
        if (source == null || stance == null) {
            throw new IllegalArgumentException("An authority conflict needs a source and a stance");
        }
    }
}
