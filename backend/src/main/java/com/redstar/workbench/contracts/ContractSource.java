package com.redstar.workbench.contracts;

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
