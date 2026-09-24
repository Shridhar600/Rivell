package com.rivell.workbench.retrieval;

import java.util.UUID;

/** The requested semantic clause region does not belong to the contract version. */
public class ClauseNotFoundException extends RuntimeException {

    public ClauseNotFoundException(UUID clauseId) {
        super("Semantic clause region not found: " + clauseId);
    }
}
