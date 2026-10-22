package com.rivell.workbench.contracts;

import java.util.UUID;

/** A failed enrichment blocks corrections until a successful re-enrichment. */
public class EnrichmentFailedException extends RuntimeException {

    private final UUID contractId;
    private final int versionNumber;

    public EnrichmentFailedException(UUID contractId, int versionNumber) {
        super("Rivell could not finish analyzing this contract. Run the analysis again first.");
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
