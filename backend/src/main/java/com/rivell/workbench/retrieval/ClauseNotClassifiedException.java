package com.rivell.workbench.retrieval;

import java.util.UUID;

/** The clause region has no LegalConcept classification, so no lanes can be planned. */
public class ClauseNotClassifiedException extends RuntimeException {

    public ClauseNotClassifiedException(UUID clauseId) {
        super("Clause region has no legal concept classification: " + clauseId);
    }
}
