package com.rivell.workbench.knowledge.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.rivell.workbench.knowledge.ArtifactType;
import com.rivell.workbench.knowledge.IngestStatus;

/**
 * API read models for the knowledge surface. Assembled from domain read models by the
 * controller layer; persistence shapes never leak into the public contract.
 */
public record KnowledgeArtifactSummaryResponse(
        UUID id,
        ArtifactType artifactType,
        String filename,
        String contentType,
        long sizeBytes,
        String sha256,
        IngestStatus ingestStatus,
        Instant createdAt) {
}
