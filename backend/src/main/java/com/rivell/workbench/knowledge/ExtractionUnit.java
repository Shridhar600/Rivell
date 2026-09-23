package com.rivell.workbench.knowledge;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One coherent source region of a knowledge artifact. Units reference the exact
 * {@link KnowledgeSourceNode}s they were derived from, keeping the provenance chain
 * candidate → unit → node(s) → artifact always resolvable.
 */
public record ExtractionUnit(
        UUID id,
        UUID artifactId,
        int ordinal,
        ExtractionUnitKind kind,
        String title,
        String contentText,
        List<UUID> sourceNodeIds) {

    public ExtractionUnit {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(artifactId, "artifactId");
        if (ordinal < 0) {
            throw new IllegalArgumentException("Ordinal must not be negative");
        }
        Objects.requireNonNull(kind, "kind");
        if (sourceNodeIds == null || sourceNodeIds.isEmpty()) {
            throw new IllegalArgumentException("Extraction unit must reference source nodes");
        }
        sourceNodeIds = List.copyOf(sourceNodeIds);
    }
}
