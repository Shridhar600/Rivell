package com.rivell.workbench.workflow.api;

import java.util.UUID;

import com.rivell.workbench.redline.ChangeActionType;
import com.rivell.workbench.workflow.DecisionAction;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Decision write body. {@code expectedVersion} is required (0 when no decision exists);
 * {@code clientRequestId} optionally makes a retried submission idempotent. ACCEPT
 * carries no action/language (the machine proposal is approved as-is); EDIT may carry
 * {@code approvedAction} and requires {@code approvedLanguage} for REPLACE/INSERT;
 * REJECT/ESCALATE require a comment.
 */
public record DecisionRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Integer expectedVersion,
        UUID clientRequestId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) DecisionAction action,
        ChangeActionType approvedAction,
        String approvedLanguage,
        String comment) {
}
