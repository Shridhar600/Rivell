package com.rivell.workbench.intelligence;

import java.util.Map;

/**
 * Provider result: neutral answers plus provider-observed facts (resolved model identity,
 * token usage). Providers report facts; the service owns execution identity and metadata.
 */
public record ProviderExecutionResult(Map<String, SemanticAnswer> answers, String resolvedModel, TokenUsage usage) {

    public ProviderExecutionResult {
        answers = Map.copyOf(answers);
        resolvedModel = resolvedModel == null || resolvedModel.isBlank() ? null : resolvedModel;
    }
}
