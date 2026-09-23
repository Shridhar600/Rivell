package com.rivell.workbench.contracts;

import java.util.UUID;

/** Enrichment is already running for this contract version. */
public class EnrichmentInProgressException extends RuntimeException {

    private final UUID contractId;
    private final int versionNumber;

    public EnrichmentInProgressException(UUID contractId, int versionNumber) {
        super("Enrichment is already running for contract " + contractId
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
