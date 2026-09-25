package com.rivell.workbench.review;

/** Explicit review configuration is invalid (unknown pack, conflicting pack sources). */
public class InvalidReviewConfigurationException extends RuntimeException {

    public InvalidReviewConfigurationException(String message) {
        super(message);
    }
}
