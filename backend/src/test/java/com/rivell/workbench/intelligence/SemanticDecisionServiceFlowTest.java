package com.rivell.workbench.intelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Service flow over a deterministic fake provider: spec/state validation, policy routing,
 * answer validation, metadata capture, and controlled failure behavior.
 */
class SemanticDecisionServiceFlowTest {

    private static final Instant START = Instant.parse("2026-09-23T10:00:00Z");

    private final MutableClock clock = new MutableClock(START);

    private SemanticDecisionService service(SemanticDecisionProvider provider) {
        return new SemanticDecisionService(
                spec -> new ModelBinding("fake", "fake-model-latest"),
                providerId -> Optional.ofNullable(provider),
                clock);
    }

    private DecisionSpec spec() {
        return new DecisionSpec("test-spec", 3, "test purpose", 1, new QuestionPack(Map.of(
                "flag", new YesNoQuestion("Is the flag set?", null, null))));
    }

    private SemanticState state() {
        return new SemanticState() {
            @Override
            public int schemaVersion() {
                return 1;
            }

            @Override
            public Object payload() {
                return Map.of("text", "synthetic");
            }
        };
    }

    @Test
    void routesThroughPolicyAndReturnsValidatedExecution() {
        var provider = new FakeSemanticDecisionProvider(Map.of(
                "flag", new YesNoAnswer(0.94)));
        var execution = service(provider).evaluate(spec(), state());

        assertThat(execution.specId()).isEqualTo("test-spec");
        assertThat(execution.specVersion()).isEqualTo(3);
        assertThat(execution.answers()).containsEntry("flag", new YesNoAnswer(0.94));

        var metadata = execution.metadata();
        assertThat(metadata.providerId()).isEqualTo("fake");
        assertThat(metadata.requestedModelAlias()).isEqualTo("fake-model-latest");
        assertThat(metadata.resolvedModel()).isEqualTo("fake-resolved-v1");
        assertThat(metadata.stateSchemaVersion()).isEqualTo(1);
        assertThat(metadata.specVersion()).isEqualTo(3);
        assertThat(metadata.outcome()).isEqualTo(DecisionOutcome.COMPLETED);
        assertThat(metadata.questionCount()).isEqualTo(1);
        assertThat(metadata.usage()).isEqualTo(new TokenUsage(10, 2));
        assertThat(metadata.startedAt()).isEqualTo(START);
        assertThat(metadata.durationMs()).isZero();

        var request = provider.lastRequest();
        assertThat(request.modelAlias()).isEqualTo("fake-model-latest");
        assertThat(request.pack().questions()).containsKey("flag");
    }

    @Test
    void rejectsStateSchemaMismatch() {
        var service = service(new FakeSemanticDecisionProvider(Map.of(
                "flag", new YesNoAnswer(0.9))));

        assertThatThrownBy(() -> service.evaluate(spec(), new SemanticState() {
            @Override
            public int schemaVersion() {
                return 2;
            }

            @Override
            public Object payload() {
                return Map.of();
            }
        })).isInstanceOf(SpecStateMismatchException.class);
    }

    @Test
    void rejectsProviderProtocolViolations() {
        var missing = service(new FakeSemanticDecisionProvider(Map.of()));

        assertThatThrownBy(() -> missing.evaluate(spec(), state()))
                .isInstanceOf(ProviderProtocolException.class)
                .hasMessageContaining("Missing answer");

        var unknownAnswer = service(new FakeSemanticDecisionProvider(Map.of(
                "flag", new YesNoAnswer(0.9), "extra", new YesNoAnswer(0.5))));

        assertThatThrownBy(() -> unknownAnswer.evaluate(spec(), state()))
                .isInstanceOf(ProviderProtocolException.class)
                .hasMessageContaining("Unexpected answer");
    }

    @Test
    void propagatesControlledProviderFailures() {
        var service = service(new FailingProvider(new ProviderTimeoutException("timeout")));

        assertThatThrownBy(() -> service.evaluate(spec(), state()))
                .isInstanceOf(ProviderTimeoutException.class);
    }

    @Test
    void wrapsUnexpectedProviderCrashesAsProtocolError() {
        var service = service(new FailingProvider(new IllegalStateException("exploded")));

        assertThatThrownBy(() -> service.evaluate(spec(), state()))
                .isInstanceOf(ProviderProtocolException.class)
                .hasMessageContaining("failed unexpectedly");
    }

    @Test
    void reportsNoConfiguredProvider() {
        var service = new SemanticDecisionService(
                spec -> new ModelBinding("missing", "alias"),
                providerId -> Optional.empty(),
                clock);

        assertThatThrownBy(() -> service.evaluate(spec(), state()))
                .isInstanceOf(NoProviderConfiguredException.class);
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private static final class FakeSemanticDecisionProvider implements SemanticDecisionProvider {

        private final Map<String, SemanticAnswer> scriptedAnswers;
        private DecisionExecutionRequest lastRequest;

        private FakeSemanticDecisionProvider(Map<String, SemanticAnswer> scriptedAnswers) {
            this.scriptedAnswers = scriptedAnswers;
        }

        private DecisionExecutionRequest lastRequest() {
            return lastRequest;
        }

        @Override
        public String id() {
            return "fake";
        }

        @Override
        public ProviderExecutionResult execute(DecisionExecutionRequest request) {
            this.lastRequest = request;
            return new ProviderExecutionResult(
                    scriptedAnswers, "fake-resolved-v1", new TokenUsage(10, 2));
        }
    }

    private static final class FailingProvider implements SemanticDecisionProvider {

        private final RuntimeException failure;

        private FailingProvider(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public String id() {
            return "fake";
        }

        @Override
        public ProviderExecutionResult execute(DecisionExecutionRequest request) {
            throw failure;
        }
    }
}
