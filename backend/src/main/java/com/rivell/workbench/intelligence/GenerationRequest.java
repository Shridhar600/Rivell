package com.rivell.workbench.intelligence;

import java.util.Map;

/**
 * Provider-facing generative request: which model alias to use, the shared state,
 * and the named typed fields to extract. Purely neutral. {@code longRead} asks for the
 * configured long-call read timeout (the whole-contract spotter), never a model or prompt
 * change.
 */
public record GenerationRequest(
        String modelAlias, SemanticState state, ReasoningSpec spec, boolean longRead) {

    public GenerationRequest(String modelAlias, SemanticState state, ReasoningSpec spec) {
        this(modelAlias, state, spec, false);
    }
}
