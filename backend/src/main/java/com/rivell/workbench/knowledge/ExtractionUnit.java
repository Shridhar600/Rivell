package com.rivell.workbench.knowledge;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One coherent source region of a knowledge artifact. Units reference the exact
 * {@link KnowledgeSourceNode}s they were derived from, keeping the provenance chain
 * candidate → unit → node(s) → artifact always resolvable. {@code recordKind} is the
 * {@code knowledge-record-kind} outcome; null until the semantic compilation decides it
 * (or when the decision could not run).
 */
public record ExtractionUnit(
        UUID id,
        UUID artifactId,
        int ordinal,
        ExtractionUnitKind kind,
        String title,
        String contentText,
        List<UUID> sourceNodeIds,
        KnowledgeRecordKind recordKind) {

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

    public ExtractionUnit withRecordKind(KnowledgeRecordKind newRecordKind) {
        return new ExtractionUnit(id, artifactId, ordinal, kind, title, contentText,
                sourceNodeIds, newRecordKind);
    }
}
