package com.rivell.workbench.contracts;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import java.time.Instant;

public record SourceArtifact(
        UUID id,
        UUID organizationId,
        String filename,
        String contentType,
        long sizeBytes,
        String sha256,
        String storageKey,
        Instant createdAt) {

    private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-f]{64}$");

    public SourceArtifact {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(organizationId, "organizationId");
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("Filename must not be blank");
        }
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException("Content type must not be blank");
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("Size must not be negative");
        }
        if (sha256 == null || !SHA256_HEX.matcher(sha256).matches()) {
            throw new IllegalArgumentException("SHA-256 must be lowercase hex");
        }
        if (storageKey == null || storageKey.isBlank()) {
            throw new IllegalArgumentException("Storage key must not be blank");
        }
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
