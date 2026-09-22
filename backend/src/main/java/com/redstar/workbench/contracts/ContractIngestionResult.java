package com.redstar.workbench.contracts;

import java.util.UUID;

public record ContractIngestionResult(
        UUID contractId,
        UUID contractVersionId,
        UUID sourceArtifactId,
        int versionNumber,
        ParseStatus parseStatus,
        String sha256,
        String storageKey,
        int nodeCount) {
}
