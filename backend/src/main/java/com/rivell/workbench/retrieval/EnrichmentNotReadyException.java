package com.rivell.workbench.retrieval;

import java.util.UUID;

/** Evidence cannot be built because the contract's semantic enrichment is not READY. */
public class EnrichmentNotReadyException extends RuntimeException {

    public EnrichmentNotReadyException(UUID contractId, int versionNumber) {
        super("Rivell has not finished analyzing this contract, so it cannot gather evidence "
                + "yet.");
    }
}
