package com.rivell.workbench.intelligence;

import java.util.Map;

/**
 * Provider-facing generative request: which model alias to use, the shared state,
 * and the named typed fields to extract. Purely neutral.
 */
public record GenerationRequest(String modelAlias, SemanticState state, ReasoningSpec spec) {
}
