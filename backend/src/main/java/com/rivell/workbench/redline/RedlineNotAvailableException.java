package com.rivell.workbench.redline;

/**
 * Trigger without a finalized approved-change set. An unknown review and an unfinalized
 * review are deliberately indistinguishable (tenant isolation).
 */
public class RedlineNotAvailableException extends RuntimeException {

    public RedlineNotAvailableException() {
        super("Finalize this review first. Rivell builds the redline from the finalized decisions.");
    }
}
