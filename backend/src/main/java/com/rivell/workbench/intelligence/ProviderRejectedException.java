package com.rivell.workbench.intelligence;

/** The provider rejected the request (authentication or validation failure). */
public class ProviderRejectedException extends SemanticDecisionException {

    public ProviderRejectedException(String message) {
        super(message);
    }
}
