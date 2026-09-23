package com.rivell.workbench.intelligence;

/**
 * Base of the controlled failure model. Domain callers only ever see these subtypes —
 * never HTTP or provider transport exceptions.
 */
public class SemanticDecisionException extends RuntimeException {

    public SemanticDecisionException(String message) {
        super(message);
    }

    public SemanticDecisionException(String message, Throwable cause) {
        super(message, cause);
    }
}
