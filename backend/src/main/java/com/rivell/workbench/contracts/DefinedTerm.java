package com.rivell.workbench.contracts;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A first-class defined term: exact source wording preserved, linked to its defining
 * node and every in-document reference found for it (deterministic matching first).
 */
public record DefinedTerm(
        UUID id,
        UUID contractVersionId,
        String term,
        String definition,
        UUID definingNodeId,
        List<UUID> sourceNodeIds,
        List<UUID> referenceNodeIds,
        boolean duplicate) {

    public DefinedTerm {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(contractVersionId, "contractVersionId");
        if (term == null || term.isBlank()) {
            throw new IllegalArgumentException("Term must not be blank");
        }
        if (definition == null || definition.isBlank()) {
            throw new IllegalArgumentException("Definition must not be blank");
        }
        Objects.requireNonNull(definingNodeId, "definingNodeId");
        sourceNodeIds = sourceNodeIds == null ? List.of() : List.copyOf(sourceNodeIds);
        referenceNodeIds(referenceNodeIds);
    }

    private static List<UUID> referenceNodeIds(List<UUID> references) {
        return references == null ? List.of() : List.copyOf(references);
    }
}
