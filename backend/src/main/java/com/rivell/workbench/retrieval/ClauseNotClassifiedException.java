package com.rivell.workbench.retrieval;

import java.util.UUID;

/** The clause region has no topic classification, so no lanes can be planned. */
public class ClauseNotClassifiedException extends RuntimeException {

    public ClauseNotClassifiedException(UUID clauseId) {
        super("Rivell has not assigned a topic to this clause yet.");
    }
}
