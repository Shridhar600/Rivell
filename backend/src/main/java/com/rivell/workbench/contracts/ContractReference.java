package com.rivell.workbench.contracts;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Explicit in-document reference from one source node to a target part. */
public record ContractReference(
        UUID id,
        UUID contractVersionId,
        UUID sourceNodeId,
        String referenceText,
        ReferenceKind kind,
        ReferenceStatus status,
        UUID targetNodeId,
        List<UUID> candidateNodeIds) {

    public ContractReference {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(contractVersionId, "contractVersionId");
        Objects.requireNonNull(sourceNodeId, "sourceNodeId");
        if (referenceText == null || referenceText.isBlank()) {
            throw new IllegalArgumentException("Reference text must not be blank");
        }
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(status, "status");
        candidateNodeIds = candidateNodeIds == null ? List.of() : List.copyOf(candidateNodeIds);
    }

    public boolean resolved() {
        return status == ReferenceStatus.RESOLVED && targetNodeId != null;
    }
}
