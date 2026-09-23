package com.rivell.workbench.intelligence;

import java.util.Map;

/**
 * Provider-facing execution request: which model alias to evaluate with, the shared state,
 * and the named questions. Purely neutral — carries no provider transport details.
 */
public record DecisionExecutionRequest(String modelAlias, SemanticState state, QuestionPack pack) {
}
