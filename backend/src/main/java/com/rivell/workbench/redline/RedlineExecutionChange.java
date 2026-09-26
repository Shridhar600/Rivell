package com.rivell.workbench.redline;

import java.util.Objects;
import java.util.UUID;

/**
 * Per-ApprovedChange execution and verification record. Carries provenance and typed
 * diagnostics only; the immutable ApprovedChange owns the approved language.
 */
public record RedlineExecutionChange(
        UUID id,
        UUID organizationId,
        UUID executionId,
        UUID approvedChangeId,
        int ordinal,
        ChangeActionType actionType,
        ChangeTargetKind targetKind,
        RedlineChangeStatus status,
        RedlineVerificationStatus verification,
        RedlineFailureCode failureCode,
        String placement,
        String resolvedTarget,
        Integer paragraphsDeleted,
        Integer paragraphsInserted,
        Long revisionIdStart,
        Long revisionIdEnd) {

    public RedlineExecutionChange {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(approvedChangeId, "approvedChangeId");
        Objects.requireNonNull(actionType, "actionType");
        Objects.requireNonNull(targetKind, "targetKind");
        Objects.requireNonNull(status, "status");
        if (ordinal < 0) {
            throw new IllegalArgumentException("ordinal must be >= 0");
        }
        if ((status == RedlineChangeStatus.APPLIED) != (verification != null)) {
            throw new IllegalArgumentException(
                    "only applied changes carry a verification state");
        }
        if (status == RedlineChangeStatus.FAILED && failureCode == null) {
            throw new IllegalArgumentException("failed changes carry a failure code");
        }
    }
}
