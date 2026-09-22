package com.redstar.workbench.contracts.api;

import java.util.UUID;

import com.redstar.workbench.contracts.ParseStatus;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Result of a contract upload and initial parse")
public record IngestResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID contractId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID contractVersionId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int versionNumber,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, implementation = ParseStatus.class) ParseStatus parseStatus,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) java.util.List<String> parseWarnings,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String filename,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 64, maxLength = 64) String sha256,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int nodeCount) {
}
