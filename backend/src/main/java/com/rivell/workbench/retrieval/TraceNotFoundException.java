package com.rivell.workbench.retrieval;

import java.util.UUID;

/** The requested retrieval trace does not exist for this organization. */
public class TraceNotFoundException extends RuntimeException {

    public TraceNotFoundException(UUID traceId) {
        super("Retrieval trace not found: " + traceId);
    }
}
