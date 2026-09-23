package com.rivell.workbench.intelligence;

/** No provider is configured for this execution. */
public class NoProviderConfiguredException extends SemanticDecisionException {

    public NoProviderConfiguredException(String message) {
        super(message);
    }
}
