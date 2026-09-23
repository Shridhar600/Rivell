package com.rivell.workbench.intelligence;

import java.util.Optional;

/**
 * Resolves provider ids to configured generative providers.
 */
public interface GenerativeProviderRegistry {

    Optional<GenerativeReasoningProvider> find(String providerId);
}
