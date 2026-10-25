package com.rivell.workbench.intelligence;

/** The provider rejected the request (authentication or validation failure). */
public class ProviderRejectedException extends SemanticDecisionException {

    private final Integer httpStatus;

    public ProviderRejectedException(String message) {
        this(message, (Integer) null);
    }

    public ProviderRejectedException(String message, Integer httpStatus) {
        super(message);
        this.httpStatus = httpStatus;
    }

    public ProviderRejectedException(String message, Throwable cause) {
        super(message, cause);
        this.httpStatus = null;
    }

    /** The HTTP status of the rejection, when one was received. */
    public Integer httpStatus() {
        return httpStatus;
    }
}
