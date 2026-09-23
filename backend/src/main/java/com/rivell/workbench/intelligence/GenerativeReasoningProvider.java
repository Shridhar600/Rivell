package com.rivell.workbench.intelligence;

/**
 * Port to an external generative model provider for bounded structured extraction.
 * Implementations live behind this boundary; domain code never sees provider transport.
 */
public interface GenerativeReasoningProvider {

    String id();

    GenerationResult generate(GenerationRequest request);
}
