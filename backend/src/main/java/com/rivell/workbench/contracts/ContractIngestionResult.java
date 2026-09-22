package com.rivell.workbench.contracts;

import java.util.UUID;
import java.util.List;

public record ContractIngestionResult(
        UUID contractId,
        UUID contractVersionId,
        UUID sourceArtifactId,
        int versionNumber,
        String filename,
        ParseStatus parseStatus,
        List<String> parseWarnings,
        String sha256,
        String storageKey,
        int nodeCount) {
}
