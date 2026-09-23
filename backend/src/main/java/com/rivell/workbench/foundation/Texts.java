package com.rivell.workbench.foundation;

/**
 * Shared text rules for untrusted input: trim-or-null normalization used across
 * manifests, notes, and scope fields. Deliberately small; this is not a dumping ground.
 */
public final class Texts {

    private Texts() {
    }

    /** Trims the value; null or blank becomes {@code null}. */
    public static String normalize(String value) {
        if (value == null) {
            return null;
        }
        var trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
