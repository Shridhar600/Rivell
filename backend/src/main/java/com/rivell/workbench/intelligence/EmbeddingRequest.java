package com.rivell.workbench.intelligence;

import java.util.List;
import java.util.Objects;

/**
 * Provider-neutral embedding request (Architecture Decision #9). The model and
 * optional dimensions are resolved by the centralized embedding policy; input texts
 * are the smallest sufficient slice. No provider-specific fields.
 */
public record EmbeddingRequest(String model, List<String> inputs, Integer dimensions) {

    public EmbeddingRequest {
        Objects.requireNonNull(model, "model");
        if (model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        Objects.requireNonNull(inputs, "inputs");
        if (dimensions != null && dimensions < 1) {
            throw new IllegalArgumentException("dimensions must be positive");
        }
    }
}
