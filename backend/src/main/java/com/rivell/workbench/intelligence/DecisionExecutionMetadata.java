package com.rivell.workbench.intelligence;

import java.time.Instant;
import java.util.UUID;
/**
 * Metadata that explains one semantic execution. Metadata-oriented by design:
 * legal state content is never included.
 */
public record DecisionExecutionMetadata(
        UUID executionId,
        String specId,
        int specVersion,
        int stateSchemaVersion,
        String providerId,
        String requestedModelAlias,
        String resolvedModel,
        Instant startedAt,
        long durationMs,
        DecisionOutcome outcome,
        int questionCount,
        TokenUsage usage) {
}
