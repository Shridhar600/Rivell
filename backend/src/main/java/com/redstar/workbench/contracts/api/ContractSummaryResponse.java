package com.redstar.workbench.contracts.api;

import java.util.UUID;

public record ContractSummaryResponse(
        UUID id,
        String title,
        LatestVersion latestVersion) {

    public record LatestVersion(
            int versionNumber,
            String parseStatus,
            String sha256,
            int nodeCount) {
    }
}
