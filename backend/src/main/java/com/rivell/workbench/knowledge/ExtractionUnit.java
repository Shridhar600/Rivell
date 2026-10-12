package com.rivell.workbench.knowledge;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One coherent source region of a knowledge artifact. Units reference the exact
 * {@link KnowledgeSourceNode}s they were derived from, keeping the provenance chain
 * candidate → unit → node(s) → artifact always resolvable. {@code recordKind} is the
 * {@code knowledge-record-kind} outcome; null until the semantic compilation decides it
 * (or when the decision could not run). {@code override} is a person's correction of that kind
 * (design 2026-10-12 §9, "make this a position"): the machine's kind stays as it was, the
 * correction and who made it sit beside it, and the effective kind is the person's.
 */
public record ExtractionUnit(
        UUID id,
        UUID artifactId,
        int ordinal,
        ExtractionUnitKind kind,
        String title,
        String contentText,
        List<UUID> sourceNodeIds,
        KnowledgeRecordKind recordKind,
        RecordKindOverride override) {

    /** A person's record-kind correction: the kind they set, who set it and when. */
    public record RecordKindOverride(KnowledgeRecordKind kind, UUID by, Instant at) {

        public RecordKindOverride {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(by, "by");
            Objects.requireNonNull(at, "at");
        }
    }

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
                sourceNodeIds, newRecordKind, override);
    }

    /** The kind that counts: a person's correction where one exists, else the machine's. */
    public KnowledgeRecordKind effectiveRecordKind() {
        return override != null ? override.kind() : recordKind;
    }
}
