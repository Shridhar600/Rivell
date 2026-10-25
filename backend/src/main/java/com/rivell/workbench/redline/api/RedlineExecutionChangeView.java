package com.rivell.workbench.redline.api;

import java.util.UUID;

import com.rivell.workbench.redline.ChangeActionType;
import com.rivell.workbench.redline.ChangeTargetKind;
import com.rivell.workbench.redline.RedlineChangeStatus;
import com.rivell.workbench.redline.RedlineFailureCode;
import com.rivell.workbench.redline.RedlineVerificationStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/** Per-approved-change execution and verification state; no OOXML internals. */
public record RedlineExecutionChangeView(
        UUID approvedChangeId,
        int ordinal,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ChangeActionType actionType,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ChangeTargetKind targetKind,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) RedlineChangeStatus status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        RedlineVerificationStatus verification,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        RedlineFailureCode failureCode,
        String placement,
        String resolvedTarget,
        Integer paragraphsDeleted,
        Integer paragraphsInserted) {
}
