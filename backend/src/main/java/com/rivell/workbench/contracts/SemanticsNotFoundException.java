package com.rivell.workbench.contracts;

import java.util.UUID;

/** No semantic contract state exists (or it is invisible to this organization). */
public class SemanticsNotFoundException extends RuntimeException {

    private final UUID contractId;
    private final int versionNumber;

    public SemanticsNotFoundException(UUID contractId, int versionNumber) {
        super("No semantic contract state for contract " + contractId
                + " version " + versionNumber);
        this.contractId = contractId;
        this.versionNumber = versionNumber;
    }

    public UUID contractId() {
        return contractId;
    }

    public int versionNumber() {
        return versionNumber;
    }
}
