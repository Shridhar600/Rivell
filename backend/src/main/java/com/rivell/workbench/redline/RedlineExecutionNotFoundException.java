package com.rivell.workbench.redline;

public class RedlineExecutionNotFoundException extends RuntimeException {

    public RedlineExecutionNotFoundException() {
        super("No redline has been generated for this review yet.");
    }
}
