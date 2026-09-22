package com.rivell.workbench.knowledge.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.rivell.workbench.knowledge.ArtifactType;
import com.rivell.workbench.knowledge.IngestStatus;

public record KnowledgeArtifactDetailResponse(
        UUID id,
        ArtifactType artifactType,
        String filename,
        String contentType,
        long sizeBytes,
        String sha256,
        String storageKey,
        Set<String> declaredContractTypes,
        String contractPackKey,
        String partyPerspective,
        String paperSource,
        String jurisdiction,
        String language,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String manifestNote,
        IngestStatus ingestStatus,
        String parserVersion,
        List<String> parseWarnings,
        Instant createdAt) {
}
