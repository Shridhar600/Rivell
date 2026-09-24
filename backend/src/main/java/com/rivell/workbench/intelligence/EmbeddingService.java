package com.rivell.workbench.intelligence;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.rivell.workbench.intelligence.provider.EmbeddingProperties;

/**
 * The single domain-facing entry point for embeddings (Architecture Decision #9).
 * Routes through the centralized provider registry and validates that complete,
 * dimension-consistent vectors reach the caller. No provider clients outside
 * intelligence; no retry storms inside the service (callers own bounded retry).
 */
public class EmbeddingService {

    private final EmbeddingProviderRegistry providerRegistry;
    private final EmbeddingProperties properties;
    private final Clock clock;

    public EmbeddingService(
            EmbeddingProviderRegistry providerRegistry,
            EmbeddingProperties properties, Clock clock) {
        this.providerRegistry = providerRegistry;
        this.properties = properties;
        this.clock = clock;
    }

    public EmbeddingExecution embed(List<String> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            throw new IllegalArgumentException("Embedding requires at least one input");
        }
        var provider = providerRegistry.find(properties.provider())
                .orElseThrow(() -> new NoProviderConfiguredException(
                        "No embedding provider configured with id " + properties.provider()));
        var startedAt = clock.instant();
        var request = new EmbeddingRequest(properties.model(), inputs, properties.dimensions());
        var batch = provider.embed(request);
        if (batch.vectors().size() != inputs.size()) {
            throw new ProviderProtocolException(
                    "Embedding provider returned " + batch.vectors().size()
                            + " vectors for " + inputs.size() + " inputs");
        }
        var metadata = new DecisionExecutionMetadata(
                UUID.randomUUID(), "embedding", 1, 1,
                properties.provider(), properties.model(), batch.resolvedModel(),
                startedAt, Duration.between(startedAt, clock.instant()).toMillis(),
                DecisionOutcome.COMPLETED, inputs.size(), batch.usage());
        return new EmbeddingExecution(metadata.executionId(), batch.vectors(),
                batch.resolvedModel(), metadata);
    }

    /** Configured embedding model identity: retrieval pins queries to exactly this. */
    public String modelIdentity() {
        return properties.provider() + ":" + properties.model()
                + (properties.dimensions() == null ? "" : ":" + properties.dimensions());
    }

    public boolean isConfigured() {
        return properties.isConfigured();
    }

    public record EmbeddingExecution(
            UUID executionId,
            List<float[]> vectors,
            String resolvedModel,
            DecisionExecutionMetadata metadata) {

        public EmbeddingExecution {
            vectors = vectors == null ? List.of() : List.copyOf(vectors);
        }
    }
}
