package com.rivell.workbench.redline;

public class RedlineExecutionNotFoundException extends RuntimeException {

    public RedlineExecutionNotFoundException() {
        super("No redline execution exists for this review");
    }
}
