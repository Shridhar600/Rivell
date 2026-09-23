package com.rivell.workbench.intelligence;

/** The provider is unreachable or unavailable (network failure, 429, 5xx). */
public class ProviderUnavailableException extends SemanticDecisionException {

    public ProviderUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
