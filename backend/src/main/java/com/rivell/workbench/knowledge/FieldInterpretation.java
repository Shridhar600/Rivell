package com.rivell.workbench.knowledge;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Provenance record of one interpreted semantic field: what the machine proposed, how it
 * was produced, which execution produced it, which source nodes support it, and whether
 * independent verification agreed. Never overwritten by human edits (RAW + INTERPRETED +
 * HUMAN CORRECTION), and never a generic map.
 */
public record FieldInterpretation(
        String field,
        InterpretationKind kind,
        String proposedValue,
        String specId,
        int specVersion,
        UUID executionId,
        List<UUID> sourceNodeIds,
        VerificationState verification,
        UUID verificationExecutionId) {

    public FieldInterpretation {
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("Field must not be blank");
        }
        if (proposedValue == null || proposedValue.isBlank()) {
            throw new IllegalArgumentException("Proposed value must not be blank");
        }
        if (specId == null || specId.isBlank()) {
            throw new IllegalArgumentException("Spec id must not be blank");
        }
        if (specVersion < 1) {
            throw new IllegalArgumentException("Spec version must be >= 1");
        }
        if (kind == InterpretationKind.GENERATIVE_EXTRACTION && verification == VerificationState.VERIFIED
                && verificationExecutionId == null) {
            throw new IllegalArgumentException(
                    "A verified generative field requires its verification execution id");
        }
        sourceNodeIds = sourceNodeIds == null ? List.of() : List.copyOf(sourceNodeIds);
    }
}
