package com.rivell.workbench.intelligence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One structured log event per model execution, metadata only: spec, provider, model, duration,
 * question count and tokens. Never state, answers or generated text. Operators and the eval read
 * cost and latency from it.
 */
final class ExecutionLog {

    static final String EVENT = "model_execution";
    private static final Logger LOG = LoggerFactory.getLogger("rivell.model-execution");

    private ExecutionLog() {
    }

    static void completed(String kind, DecisionExecutionMetadata metadata) {
        var usage = metadata.usage();
        LOG.atInfo()
                .addKeyValue("kind", kind)
                .addKeyValue("spec", metadata.specId())
                .addKeyValue("specVersion", metadata.specVersion())
                .addKeyValue("provider", metadata.providerId())
                .addKeyValue("model", metadata.resolvedModel())
                .addKeyValue("outcome", metadata.outcome().name())
                .addKeyValue("durationMs", metadata.durationMs())
                .addKeyValue("questions", metadata.questionCount())
                .addKeyValue("inputTokens", usage == null ? null : usage.inputTokens())
                .addKeyValue("outputTokens", usage == null ? null : usage.outputTokens())
                .log(EVENT);
    }

    /** One failed attempt that {@link ProviderRetry} will repeat; not a failed execution. */
    static void retried(String kind, String specId, int specVersion, String providerId,
            RuntimeException failure) {
        LOG.atInfo()
                .addKeyValue("kind", kind)
                .addKeyValue("spec", specId)
                .addKeyValue("specVersion", specVersion)
                .addKeyValue("provider", providerId)
                .addKeyValue("outcome", "RETRIED")
                .addKeyValue("error", failure.getClass().getSimpleName())
                .addKeyValue("reason", failure.getMessage())
                .log(EVENT);
    }

    static void failed(String kind, String specId, int specVersion, String providerId,
            long durationMs, RuntimeException failure) {
        LOG.atWarn()
                .addKeyValue("kind", kind)
                .addKeyValue("spec", specId)
                .addKeyValue("specVersion", specVersion)
                .addKeyValue("provider", providerId)
                .addKeyValue("outcome", "FAILED")
                .addKeyValue("durationMs", durationMs)
                .addKeyValue("error", failure.getClass().getSimpleName())
                // code-written reasons only ("missing required field x"); a raw provider
                // exception may carry contract text and is never logged
                .addKeyValue("reason", failure instanceof SemanticDecisionException ? failure.getMessage() : null)
                .log(EVENT);
    }
}
