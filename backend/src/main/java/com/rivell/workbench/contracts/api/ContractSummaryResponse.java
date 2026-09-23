package com.rivell.workbench.contracts.api;

import java.util.UUID;

import com.rivell.workbench.contracts.ParseStatus;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Contract with its latest version state")
public record ContractSummaryResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String title,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LatestVersion latestVersion) {

    @Schema(description = "Latest version of the contract")
    public record LatestVersion(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int versionNumber,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, implementation = ParseStatus.class) ParseStatus parseStatus,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> parseWarnings,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String parserVersion,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 64, maxLength = 64) String sha256,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String filename,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int nodeCount) {
    }
}
