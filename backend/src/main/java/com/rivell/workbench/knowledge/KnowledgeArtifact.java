package com.rivell.workbench.knowledge;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * An immutable uploaded legal knowledge source. Original bytes are never mutated;
 * {@code sha256} identifies the exact stored content (Architecture Decision #11).
 * The human-declared artifact type remains authoritative; the machine assessment is
 * advisory evidence retained beside it.
 */
public record KnowledgeArtifact(
        UUID id,
        UUID organizationId,
        ArtifactType artifactType,
        String filename,
        String contentType,
        long sizeBytes,
        String sha256,
        String storageKey,
        ArtifactManifest manifest,
        IngestStatus ingestStatus,
        String parserVersion,
        List<String> parseWarnings,
        ArtifactTypeAssessment assessment,
        Instant createdAt) {

    public KnowledgeArtifact {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(artifactType, "artifactType");
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("Filename must not be blank");
        }
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException("Content type must not be blank");
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("Size must not be negative");
        }
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(storageKey, "storageKey");
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(ingestStatus, "ingestStatus");
        parseWarnings = parseWarnings == null ? List.of() : List.copyOf(parseWarnings);
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public KnowledgeArtifact withIngestOutcome(
            String parserVersion, IngestStatus status, List<String> warnings) {
        return new KnowledgeArtifact(id, organizationId, artifactType, filename, contentType,
                sizeBytes, sha256, storageKey, manifest, status, parserVersion, warnings,
                assessment, createdAt);
    }

    public KnowledgeArtifact withAssessment(ArtifactTypeAssessment assessed) {
        return new KnowledgeArtifact(id, organizationId, artifactType, filename, contentType,
                sizeBytes, sha256, storageKey, manifest, ingestStatus, parserVersion,
                parseWarnings, assessed, createdAt);
    }
}
