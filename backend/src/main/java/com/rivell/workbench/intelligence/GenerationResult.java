package com.rivell.workbench.intelligence;

import java.util.Map;

/**
 * Provider result: extracted field values plus provider-observed facts. Values are
 * validated for completeness before reaching the caller.
 */
public record GenerationResult(Map<String, String> fields, String resolvedModel, TokenUsage usage) {

    public GenerationResult {
        fields = Map.copyOf(fields);
        resolvedModel = resolvedModel == null || resolvedModel.isBlank() ? null : resolvedModel;
    }
}
