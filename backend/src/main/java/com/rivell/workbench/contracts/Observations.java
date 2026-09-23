package com.rivell.workbench.contracts;

/**
 * Safe enrichment observations (counts only — never contract text): the eval/telemetry
 * basis per AD #30, never used as an idempotency or quality score.
 */
public record Observations(
        int regionCount,
        int decisionExecutionCount,
        int questionCount,
        int generativeExtractionCount,
        int deterministicFactCount,
        boolean providerDegraded,
        boolean budgetExhausted) {
}
