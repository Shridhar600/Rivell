package com.rivell.workbench.redline;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One verified generated redline artifact (bytes live in DocumentStorage). */
public record RedlineArtifact(
        UUID id,
        UUID organizationId,
        UUID executionId,
        String filename,
        String contentType,
        long sizeBytes,
        String sha256,
        String storageKey,
        Instant createdAt) {

    public static final String DOCX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    public RedlineArtifact {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(executionId, "executionId");
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("filename must not be blank");
        }
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException("content type must not be blank");
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("size must be >= 0");
        }
        if (sha256 == null || !sha256.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("SHA-256 must be lowercase hex");
        }
        if (storageKey == null || storageKey.isBlank()) {
            throw new IllegalArgumentException("storage key must not be blank");
        }
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
