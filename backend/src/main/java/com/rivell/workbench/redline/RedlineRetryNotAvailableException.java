package com.rivell.workbench.redline;

/** Retry Redline was asked for on a review that has no recorded failure to retry. */
public class RedlineRetryNotAvailableException extends RuntimeException {

    public RedlineRetryNotAvailableException(String message) {
        super(message);
    }
}
