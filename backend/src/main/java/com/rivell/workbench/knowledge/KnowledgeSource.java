package com.rivell.workbench.knowledge;

import java.util.Objects;

/**
 * Stable identity of the exact original bytes of a knowledge artifact, used for
 * retrieval from {@code DocumentStorage}.
 */
public record KnowledgeSource(
        String filename,
        String contentType,
        String sha256,
        String storageKey) {

    public KnowledgeSource {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("Filename must not be blank");
        }
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException("Content type must not be blank");
        }
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(storageKey, "storageKey");
    }
}
