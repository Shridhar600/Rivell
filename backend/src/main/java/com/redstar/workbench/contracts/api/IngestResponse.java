package com.redstar.workbench.contracts.api;

import java.util.UUID;

public record IngestResponse(
        UUID contractId,
        UUID contractVersionId,
        int versionNumber,
        String parseStatus,
        String sha256,
        int nodeCount) {
}
