package com.rivell.workbench.intelligence;

import java.util.List;
import java.util.Objects;

/**
 * Provider-neutral embedding result: vectors in request order, the resolved model
 * identity (provider fact), and usage where the provider reports it.
 */
public record EmbeddingBatch(List<float[]> vectors, String resolvedModel, TokenUsage usage) {

    public EmbeddingBatch {
        vectors = vectors == null ? List.of() : List.copyOf(vectors);
        Objects.requireNonNull(vectors, "vectors");
        if (resolvedModel == null || resolvedModel.isBlank()) {
            throw new IllegalArgumentException("resolvedModel must not be blank");
        }
    }
}
