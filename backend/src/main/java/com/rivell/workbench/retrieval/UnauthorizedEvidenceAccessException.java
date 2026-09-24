package com.rivell.workbench.retrieval;

import java.util.UUID;

/**
 * The current identity may only build evidence for its own organization; any other
 * request is indistinguishable from an unauthorized access (tenant pattern).
 */
public class UnauthorizedEvidenceAccessException extends RuntimeException {

    public UnauthorizedEvidenceAccessException(UUID userId) {
        super("User " + userId + " is not authorized to build evidence for this organization");
    }
}
