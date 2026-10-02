package com.rivell.workbench.contracts.api;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One contract in the organization list. The latest-version block reuses the established
 * single-contract shape rather than restating it, so the two contract surfaces cannot
 * drift apart.
 */
@Schema(description = "Contract with its latest version state")
public record ContractListItemResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String title,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        ContractSummaryResponse.LatestVersion latestVersion) {
}
