package com.rivell.workbench.redline;

/**
 * The frozen source of an approved change set cannot be resolved or is corrupt. This is
 * an infrastructure/data failure, not a document-execution failure: no execution row is
 * persisted, so an operator can repair the source and re-trigger.
 */
public class RedlineSourceUnavailableException extends RuntimeException {

    private final RedlineFailureCode code;

    public RedlineSourceUnavailableException(RedlineFailureCode code, String message) {
        super(message);
        this.code = code;
    }

    public RedlineFailureCode code() {
        return code;
    }
}
