package com.rivell.workbench.redline;

import java.util.UUID;

/** Typed renderer failure; messages are sanitized and never contain document text. */
public class RedlineRenderException extends RuntimeException {

    private final RedlineFailureCode code;
    private final UUID approvedChangeId;

    public RedlineRenderException(RedlineFailureCode code, UUID approvedChangeId,
            String message) {
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
