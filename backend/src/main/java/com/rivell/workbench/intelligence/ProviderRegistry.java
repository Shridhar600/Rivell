package com.rivell.workbench.intelligence;

import java.util.Map;
import java.util.Optional;

/**
 * Resolves provider ids to configured providers. Implementations must not create clients
 * per request; providers are registered beans.
 */
public interface ProviderRegistry {

    Optional<SemanticDecisionProvider> find(String providerId);
}
