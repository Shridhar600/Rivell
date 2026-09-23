package com.rivell.workbench.intelligence;

/**
 * Routing decision made by the centralized model policy: which provider and which
 * model alias handle a decision spec.
 */
public record ModelBinding(String providerId, String modelAlias) {

    public ModelBinding {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("Provider id must not be blank");
        }
        if (modelAlias == null || modelAlias.isBlank()) {
            throw new IllegalArgumentException("Model alias must not be blank");
        }
    }
}
