package com.rivell.workbench.review;

/**
 * Per-run execution totals for the three states a list row reports. Aggregated in SQL over
 * the whole page so a row never hydrates its executions individually.
 */
public record ReviewExecutionCounts(int completed, int degraded, int failed) {
}
