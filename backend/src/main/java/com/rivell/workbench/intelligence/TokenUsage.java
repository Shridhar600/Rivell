package com.rivell.workbench.intelligence;

/**
 * Provider-reported token usage for one execution.
 */
public record TokenUsage(int inputTokens, int outputTokens) {
}
