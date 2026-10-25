package com.rivell.workbench.intelligence;

/**
 * Provider-neutral lookup of one clause text's embedding. The review run supplies a
 * run-scoped cache so the precedent lane and position search never embed the same text twice;
 * outside a review the direct per-call path is unchanged.
 */
@FunctionalInterface
public interface EmbeddingLookup {

    float[] vector(String text);
}
