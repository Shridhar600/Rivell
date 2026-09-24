package com.rivell.workbench.intelligence;

/**
 * Provider adapter for embeddings (Architecture Decision #9). Adapters live inside
 * the intelligence module; retrieval sees only provider-neutral batches. The third
 * centralized intelligence capability alongside bounded decisions and generative
 * reasoning.
 */
public interface EmbeddingProvider {

    String id();

    EmbeddingBatch embed(EmbeddingRequest request);
}
