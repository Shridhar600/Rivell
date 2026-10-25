package com.rivell.workbench.intelligence;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The single domain-facing entry point for bounded semantic judgments. Validates spec/state
 * compatibility, routes through the centralized model policy and provider registry, and
 * guarantees that only complete, type-correct answers reach the caller.
 */
public class SemanticDecisionService {

    private final ModelPolicy modelPolicy;
    private final ProviderRegistry providerRegistry;
    private final Clock clock;
    private final ProviderRetry retry;

    public SemanticDecisionService(ModelPolicy modelPolicy, ProviderRegistry providerRegistry, Clock clock) {
        this(modelPolicy, providerRegistry, clock, ProviderRetry.NONE);
    }

    public SemanticDecisionService(
            ModelPolicy modelPolicy, ProviderRegistry providerRegistry, Clock clock, ProviderRetry retry) {
        this.modelPolicy = modelPolicy;
        this.providerRegistry = providerRegistry;
        this.clock = clock;
        this.retry = retry;
    }

    public DecisionExecution evaluate(DecisionSpec spec, SemanticState state) {
        if (state.schemaVersion() != spec.stateSchemaVersion()) {
            throw new SpecStateMismatchException(
                    "Decision spec " + spec.id() + " v" + spec.version() + " expects state schema v"
                            + spec.stateSchemaVersion() + " but received v" + state.schemaVersion());
        }

        var binding = modelPolicy.resolve(spec);
        var provider = providerRegistry.find(binding.providerId())
                .orElseThrow(() -> new NoProviderConfiguredException(
                        "No provider configured with id " + binding.providerId()));

        var startedAt = clock.instant();
        try {
            var request = new DecisionExecutionRequest(binding.modelAlias(), state, spec.pack());
            var result = retry.call(binding.providerId(), () -> provider.execute(request),
                    event -> ExecutionLog.retried(
                            "decision", spec.id(), spec.version(), binding.providerId(), event));
            validateAnswers(spec, result.answers());
            var metadata = completedMetadata(spec, binding, result, questionCount(spec), startedAt);
            ExecutionLog.completed("decision", metadata);
            return new DecisionExecution(
                    metadata.executionId(), spec.id(), spec.version(), result.answers(), metadata);
        } catch (SemanticDecisionException e) {
            logFailure(spec, binding, startedAt, e);
            throw e;
        } catch (RuntimeException e) {
            logFailure(spec, binding, startedAt, e);
            throw new ProviderProtocolException(
                    "Provider " + binding.providerId() + " failed unexpectedly: " + e.getClass().getSimpleName(), e);
        }
    }

    private void validateAnswers(DecisionSpec spec, Map<String, SemanticAnswer> answers) {
        var pack = spec.pack().questions();
        for (var name : pack.keySet()) {
            var answer = answers.get(name);
            if (answer == null) {
                throw new ProviderProtocolException("Missing answer for question " + name);
            }
        }
        for (var name : answers.keySet()) {
            if (!pack.containsKey(name)) {
                throw new ProviderProtocolException("Unexpected answer for unknown question " + name);
            }
        }
    }

    private DecisionExecutionMetadata completedMetadata(
            DecisionSpec spec, ModelBinding binding, ProviderExecutionResult result,
            int questionCount, Instant startedAt) {
        return new DecisionExecutionMetadata(
                UUID.randomUUID(), spec.id(), spec.version(), spec.stateSchemaVersion(),
                binding.providerId(), binding.modelAlias(), result.resolvedModel(),
                startedAt, Duration.between(startedAt, clock.instant()).toMillis(),
                DecisionOutcome.COMPLETED, questionCount, result.usage());
    }

    private void logFailure(DecisionSpec spec, ModelBinding binding, Instant startedAt,
            RuntimeException failure) {
        ExecutionLog.failed("decision", spec.id(), spec.version(), binding.providerId(),
                Duration.between(startedAt, clock.instant()).toMillis(), failure);
    }

    private static int questionCount(DecisionSpec spec) {
        return spec.pack().questions().size();
    }
}
