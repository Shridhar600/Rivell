package com.rivell.workbench.intelligence;

/** The provider is unreachable or unavailable (network failure, 429, 5xx). */
public class ProviderUnavailableException extends SemanticDecisionException {

    private final Integer httpStatus;

    public ProviderUnavailableException(String message) {
        this(message, (Integer) null);
    }

    public ProviderUnavailableException(String message, Integer httpStatus) {
        super(message);
        this.httpStatus = httpStatus;
    }

    public ProviderUnavailableException(String message, Throwable cause) {
        super(message, cause);
        this.httpStatus = null;
    }

    /** The HTTP status of the failed attempt, when the failure came from a response. */
    public Integer httpStatus() {
        return httpStatus;
    }
}
