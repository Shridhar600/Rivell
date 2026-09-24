package com.rivell.workbench.retrieval;

import java.util.UUID;

/** Evidence cannot be built because the contract's semantic enrichment is not READY. */
public class EnrichmentNotReadyException extends RuntimeException {

    public EnrichmentNotReadyException(UUID contractId, int versionNumber) {
        super("Contract " + contractId + " v" + versionNumber
                + " is not enriched READY; evidence cannot be built");
    }
}
