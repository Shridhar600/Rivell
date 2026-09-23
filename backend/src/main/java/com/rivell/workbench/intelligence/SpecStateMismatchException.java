package com.rivell.workbench.intelligence;

/** The state schema does not match the decision spec's expected schema version. */
public class SpecStateMismatchException extends SemanticDecisionException {

    public SpecStateMismatchException(String message) {
        super(message);
    }
}
