package com.rivell.workbench.knowledge;

/** The semantic classification could not be completed; the request carries no proposal. */
public class ConceptClassificationUnavailableException extends RuntimeException {

    public ConceptClassificationUnavailableException(String message) {
        super(message);
    }

    public ConceptClassificationUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
