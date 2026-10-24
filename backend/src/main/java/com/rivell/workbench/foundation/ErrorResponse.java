package com.rivell.workbench.foundation;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Error returned by contract API failures")
public record ErrorResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String code,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String title,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String detail,
        /**
         * A typed failure code, when the error carries one the client can act on (DW-037 /
         * RED-SRC). Null, and absent from the body, for every error without one.
         */
        @Schema(description = "Typed failure code, when the error carries one; absent otherwise")
        @JsonInclude(JsonInclude.Include.NON_NULL) String failureCode) {

    public ErrorResponse(String code, String title, String detail) {
        this(code, title, detail, null);
    }
}
