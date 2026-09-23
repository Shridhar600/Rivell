package com.rivell.workbench.intelligence;

/**
 * Coarse execution outcome classification. Detailed causes live on the thrown exception.
 */
public enum DecisionOutcome {
    COMPLETED,
    PROVIDER_ERROR,
    TIMEOUT,
    PROTOCOL_ERROR,
    VALIDATION_ERROR
}
