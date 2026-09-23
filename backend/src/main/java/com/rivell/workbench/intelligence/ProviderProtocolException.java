package com.rivell.workbench.intelligence;

/** The provider returned an unusable response: missing answer, wrong primitive, malformed probabilities. */
public class ProviderProtocolException extends SemanticDecisionException {

    public ProviderProtocolException(String message) {
        super(message);
    }

    public ProviderProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
