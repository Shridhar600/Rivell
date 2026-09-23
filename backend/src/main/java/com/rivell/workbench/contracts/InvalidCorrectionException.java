package com.rivell.workbench.contracts;

import java.util.UUID;

/** A human correction violates the semantic model's invariants. */
public class InvalidCorrectionException extends RuntimeException {

    private final UUID clauseId;

    public InvalidCorrectionException(UUID clauseId, String message) {
        super(message);
        this.clauseId = clauseId;
    }

    public UUID clauseId() {
        return clauseId;
    }
}
