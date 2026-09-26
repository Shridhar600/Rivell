package com.rivell.workbench.redline;

import java.util.UUID;

/**
 * Typed plan-resolution failure. The message is code-owned and sanitized: it never
 * contains document text or approved language.
 */
public class RedlinePlanException extends RuntimeException {

    private final RedlineFailureCode code;
    private final UUID approvedChangeId;

    public RedlinePlanException(RedlineFailureCode code, UUID approvedChangeId, String message) {
        super(message);
        this.code = code;
        this.approvedChangeId = approvedChangeId;
    }

    public RedlineFailureCode code() {
        return code;
    }

    public UUID approvedChangeId() {
        return approvedChangeId;
    }
}
