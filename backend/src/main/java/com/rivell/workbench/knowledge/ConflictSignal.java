package com.rivell.workbench.knowledge;

import java.util.Objects;
import java.util.UUID;

/**
 * One detected duplicate/conflict signal about a candidate, resolved against currently
 * published organizational knowledge.
 */
public record ConflictSignal(
        ConflictType conflictType,
        String message,
        UUID publishedCandidateId) {

    public ConflictSignal {
        Objects.requireNonNull(conflictType, "conflictType");
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Message must not be blank");
        }
    }
}
