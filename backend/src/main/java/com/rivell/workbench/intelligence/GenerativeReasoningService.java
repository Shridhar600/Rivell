package com.rivell.workbench.intelligence;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The single domain-facing entry point for bounded generative extraction. Validates
 * spec/state compatibility, routes through the centralized model policy and provider
 * registry, and guarantees that only complete, non-blank field values reach the caller.
 * No conversation memory, no tool calling, no agent loops.
 */
public class GenerativeReasoningService {

    private final ModelPolicy modelPolicy;
    private final GenerativeProviderRegistry providerRegistry;
    private final Clock clock;
    private final ProviderRetry retry;

    public GenerativeReasoningService(ModelPolicy modelPolicy, GenerativeProviderRegistry providerRegistry, Clock clock) {
        this(modelPolicy, providerRegistry, clock, ProviderRetry.NONE);
    }

    public GenerativeReasoningService(
            ModelPolicy modelPolicy, GenerativeProviderRegistry providerRegistry, Clock clock, ProviderRetry retry) {
        this.modelPolicy = modelPolicy;
        this.providerRegistry = providerRegistry;
        this.clock = clock;
        this.retry = retry;
    }

    public GenerativeExecution generate(ReasoningSpec spec, SemanticState state) {
        return generate(spec, state, false);
    }

    /**
     * A generative call that may legitimately run longer than an ordinary one (the
     * whole-contract spotter on a slow model). The spec, prompt and validation are unchanged.
     */
    public GenerativeExecution generateLong(ReasoningSpec spec, SemanticState state) {
        return generate(spec, state, true);
    }

    private GenerativeExecution generate(
            ReasoningSpec spec, SemanticState state, boolean longRead) {
        if (state.schemaVersion() != spec.stateSchemaVersion()) {
            throw new SpecStateMismatchException(
                    "Reasoning spec " + spec.id() + " v" + spec.version() + " expects state schema v"
                            + spec.stateSchemaVersion() + " but received v" + state.schemaVersion());
        }

        var binding = modelPolicy.resolve(spec);
        var provider = providerRegistry.find(binding.providerId())
                .orElseThrow(() -> new NoProviderConfiguredException(
                        "No provider configured with id " + binding.providerId()));

        var startedAt = clock.instant();
        try {
            var request = new GenerationRequest(binding.modelAlias(), state, spec, longRead);
            var result = retry.call(binding.providerId(), () -> provider.generate(request),
                    event -> ExecutionLog.retried(
                            "generation", spec.id(), spec.version(), binding.providerId(), event));
            validateFields(spec, result.fields());
            var metadata = new DecisionExecutionMetadata(
                    UUID.randomUUID(), spec.id(), spec.version(), spec.stateSchemaVersion(),
                    binding.providerId(), binding.modelAlias(), result.resolvedModel(),
                    startedAt, Duration.between(startedAt, clock.instant()).toMillis(),
                    DecisionOutcome.COMPLETED, spec.fields().size(), result.usage());
            ExecutionLog.completed("generation", metadata);
            return new GenerativeExecution(
                    metadata.executionId(), result.fields(), metadata);
        } catch (SemanticDecisionException e) {
            ExecutionLog.failed("generation", spec.id(), spec.version(), binding.providerId(),
                    Duration.between(startedAt, clock.instant()).toMillis(), e);
            throw e;
        } catch (RuntimeException e) {
            ExecutionLog.failed("generation", spec.id(), spec.version(), binding.providerId(),
                    Duration.between(startedAt, clock.instant()).toMillis(), e);
            throw new ProviderProtocolException(
                    "Generative provider " + binding.providerId() + " failed unexpectedly: "
                            + e.getClass().getSimpleName(), e);
        }
    }

    private void validateFields(ReasoningSpec spec, Map<String, String> fields) {
        for (var name : spec.fields().keySet()) {
            var value = fields.get(name);
            if (value == null || value.isBlank()) {
                throw new ProviderProtocolException(
                        "Generative result is missing required field " + name);
            }
        }
        for (var name : fields.keySet()) {
            if (!spec.fields().containsKey(name)) {
                throw new ProviderProtocolException(
                        "Generative result contains unknown field " + name);
            }
        }
        for (var entry : spec.allowedValues().entrySet()) {
            var value = fields.get(entry.getKey());
            if (value != null && !entry.getValue().contains(value)) {
                throw new ProviderProtocolException(
                        "Generative result field " + entry.getKey()
                                + " is outside its allowed values");
            }
        }
    }

    public record GenerativeExecution(
            java.util.UUID executionId,
            Map<String, String> fields,
            DecisionExecutionMetadata metadata) {
    }
}
