package com.rivell.workbench.intelligence;

/** The requested decision spec does not exist in the configured registry. */
public class UnknownDecisionSpecException extends SemanticDecisionException {

    public UnknownDecisionSpecException(String message) {
        super(message);
    }
}
