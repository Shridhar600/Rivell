package com.rivell.workbench.intelligence;

import java.util.Optional;

/**
 * Resolves provider ids to configured embedding providers.
 */
public interface EmbeddingProviderRegistry {

    Optional<EmbeddingProvider> find(String providerId);
}
