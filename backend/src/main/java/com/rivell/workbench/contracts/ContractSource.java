package com.rivell.workbench.contracts;

import java.util.UUID;

public record ContractSource(
        UUID id,
        UUID organizationId,
        String filename,
        String contentType,
        long sizeBytes,
        String sha256,
        String storageKey) {
}
