package com.rivell.workbench.foundation;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Error returned by contract API failures")
public record ErrorResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String code,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String title,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String detail) {
}
