package com.rivell.workbench.intelligence;

/** The provider did not respond in time. */
public class ProviderTimeoutException extends SemanticDecisionException {

    public ProviderTimeoutException(String message) {
        super(message);
    }
}
