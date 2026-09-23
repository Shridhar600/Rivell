package com.rivell.workbench.intelligence;

import java.util.Map;
import java.util.UUID;

/**
 * Complete result of one semantic decision: neutral answers plus the metadata needed
 * to explain and audit the execution.
 */
public record DecisionExecution(UUID executionId, String specId, int specVersion,
        Map<String, SemanticAnswer> answers, DecisionExecutionMetadata metadata) {

    public DecisionExecution {
        answers = Map.copyOf(answers);
    }
}
