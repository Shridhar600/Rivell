package com.rivell.workbench.intelligence.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rivell.workbench.intelligence.ChoiceAnswer;
import com.rivell.workbench.intelligence.ChoiceQuestion;
import com.rivell.workbench.intelligence.DecisionExecutionRequest;
import com.rivell.workbench.intelligence.ProviderProtocolException;
import com.rivell.workbench.intelligence.QuestionPack;
import com.rivell.workbench.intelligence.SemanticQuestion;
import com.rivell.workbench.intelligence.SemanticState;
import com.rivell.workbench.intelligence.YesNoAnswer;
import com.rivell.workbench.intelligence.YesNoQuestion;

class OpenAiCompatibleDecisionProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GenerativeProperties properties = new GenerativeProperties(
            "openai-compatible", "https://api.example.test/v1", "test-key", "gpt-test",
            null, null, Duration.ofSeconds(1), Duration.ofSeconds(1));

    private DecisionExecutionRequest request() {
        var questions = new LinkedHashMap<String, SemanticQuestion>();
        questions.put("needs_review", new YesNoQuestion("Is the clause unusual and against us?",
                "It restricts us.", "It is normal."));
        questions.put("position_1", new ChoiceQuestion("How does the clause compare?",
                Map.of("MEETS_PREFERRED", "Consistent.", "WALK_AWAY", "Forbidden.", "UNCLEAR", "Unclear.")));
        SemanticState state = new SemanticState() {
            @Override
            public int schemaVersion() {
                return 1;
            }

            @Override
            public Object payload() {
                return Map.of("clause", "The only remedy is damages.");
            }
        };
        return new DecisionExecutionRequest("gpt-test", state, new QuestionPack(questions));
    }

    private static String response(String content) {
        return """
                {"model":"gpt-test-2026","choices":[{"message":{"role":"assistant","content":%s},
                "finish_reason":"stop"}],"usage":{"prompt_tokens":120,"completion_tokens":30}}
                """.formatted(new com.fasterxml.jackson.databind.node.TextNode(content).toString());
    }

    @Test
    void asksEveryQuestionUnderAStrictSchemaAndMapsConfidenceToProbability() throws Exception {
        var sent = new AtomicReference<String>();
        var provider = new OpenAiCompatibleDecisionProvider((path, payload) -> {
            sent.set(payload);
            return response("""
                    {"needs_review":{"answer":"no","confidence":0.9},
                     "position_1":{"answer":"WALK_AWAY","confidence":0.8}}""");
        }, objectMapper, properties);

        var result = provider.execute(request());

        var body = objectMapper.readTree(sent.get());
        assertThat(body.path("model").asText()).isEqualTo("gpt-test");
        var schema = body.path("response_format").path("json_schema");
        assertThat(schema.path("strict").asBoolean()).isTrue();
        assertThat(schema.path("schema").path("required").toString())
                .contains("needs_review").contains("position_1");
        assertThat(schema.path("schema").path("properties").path("position_1").path("properties")
                .path("answer").path("enum").toString()).contains("WALK_AWAY").contains("UNCLEAR");
        assertThat(result.answers().get("needs_review")).isInstanceOfSatisfying(YesNoAnswer.class,
                yesNo -> assertThat(yesNo.probability()).isCloseTo(0.1, org.assertj.core.data.Offset.offset(1e-9)));
        assertThat(result.answers().get("position_1")).isInstanceOfSatisfying(ChoiceAnswer.class, choice -> {
            assertThat(choice.selected()).isEqualTo("WALK_AWAY");
            assertThat(choice.probabilities()).containsEntry("WALK_AWAY", 0.8);
            assertThat(choice.probabilities().get("UNCLEAR")).isCloseTo(0.1, org.assertj.core.data.Offset.offset(1e-9));
        });
        assertThat(result.usage().inputTokens()).isEqualTo(120);
    }

    @Test
    void disabledReasoningIsSentAsEffortNone() throws Exception {
        var sent = new AtomicReference<String>();
        var noReasoning = new GenerativeProperties(
                "openai-compatible", "https://api.example.test/v1", "test-key", "gpt-test",
                null, null, Duration.ofSeconds(1), Duration.ofSeconds(1), null,
                new GenerativeProperties.Reasoning(false, null));
        var provider = new OpenAiCompatibleDecisionProvider((path, payload) -> {
            sent.set(payload);
            return response("""
                    {"needs_review":{"answer":"no","confidence":0.9},
                     "position_1":{"answer":"WALK_AWAY","confidence":0.8}}""");
        }, objectMapper, noReasoning);

        provider.execute(request());

        assertThat(objectMapper.readTree(sent.get()).path("reasoning_effort").asText())
                .isEqualTo("none");
    }

    @Test
    void unsetReasoningSendsNoEffortField() throws Exception {
        var sent = new AtomicReference<String>();
        var provider = new OpenAiCompatibleDecisionProvider((path, payload) -> {
            sent.set(payload);
            return response("""
                    {"needs_review":{"answer":"no","confidence":0.9},
                     "position_1":{"answer":"WALK_AWAY","confidence":0.8}}""");
        }, objectMapper, properties);

        provider.execute(request());

        assertThat(objectMapper.readTree(sent.get()).has("reasoning_effort")).isFalse();
    }

    @Test
    void anAnswerOutsideTheOfferedOptionsIsAProtocolError() {
        var provider = new OpenAiCompatibleDecisionProvider((path, payload) -> response("""
                {"needs_review":{"answer":"yes","confidence":0.7},
                 "position_1":{"answer":"MAYBE","confidence":0.8}}"""), objectMapper, properties);

        assertThatThrownBy(() -> provider.execute(request()))
                .isInstanceOf(ProviderProtocolException.class)
                .hasMessageContaining("not an offered option");
    }

    @Test
    void everyBillingCodeAndTheLegacyQuotaTypeAreOutOfCredit() {
        for (var body : List.of(
                "{\"error\":{\"code\":\"credit_balance_exhausted\"}}",
                "{\"error\":{\"code\":\"organization_spend_limit_exceeded\"}}",
                "{\"error\":{\"code\":\"project_spend_limit_exceeded\"}}",
                "{\"error\":{\"code\":\"organization_usage_limit_exceeded\"}}",
                "{\"error\":{\"type\":\"insufficient_quota\"}}")) {
            var provider = new OpenAiCompatibleDecisionProvider((path, payload) -> {
                throw new org.springframework.web.client.RestClientResponseException(
                        "rejected", 429, "Too Many Requests", null,
                        body.getBytes(java.nio.charset.StandardCharsets.UTF_8), null);
            }, objectMapper, properties);

            assertThatThrownBy(() -> provider.execute(request()))
                    .as(body)
                    .isInstanceOf(com.rivell.workbench.intelligence.ProviderOutOfCreditException.class);
        }
    }

    @Test
    void aBillingCodeIsOutOfCreditAndShortCircuitsTheProvider() {
        var clock = new TestClock();
        var guard = new com.rivell.workbench.intelligence.ProviderCreditGuard(clock);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var body = "{\"error\":{\"code\":\"project_spend_limit_exceeded\"}}";
        var provider = new OpenAiCompatibleDecisionProvider((path, payload) -> {
            calls.incrementAndGet();
            throw new org.springframework.web.client.RestClientResponseException(
                    "rejected", 429, "Too Many Requests", null,
                    body.getBytes(java.nio.charset.StandardCharsets.UTF_8), null);
        }, objectMapper, properties, guard);

        assertThatThrownBy(() -> provider.execute(request()))
                .isInstanceOf(com.rivell.workbench.intelligence.ProviderOutOfCreditException.class);
        assertThat(calls).hasValue(1);
        assertThatThrownBy(() -> provider.execute(request()))
                .isInstanceOf(com.rivell.workbench.intelligence.ProviderOutOfCreditException.class);
        assertThat(calls).hasValue(1);

        clock.advance(Duration.ofSeconds(61));
        assertThatThrownBy(() -> provider.execute(request()))
                .isInstanceOf(com.rivell.workbench.intelligence.ProviderOutOfCreditException.class);
        assertThat(calls).hasValue(2);
    }

    @Test
    void slowDownAndAPlain429CarryRetryAfter() {
        for (var code : List.of("slow_down", "rate_limit_exceeded")) {
            var headers = new org.springframework.http.HttpHeaders();
            headers.set(org.springframework.http.HttpHeaders.RETRY_AFTER, "7");
            var provider = new OpenAiCompatibleDecisionProvider((path, payload) -> {
                throw new org.springframework.web.client.RestClientResponseException(
                        "rejected", 429, "Too Many Requests", headers,
                        ("{\"error\":{\"code\":\"" + code + "\"}}")
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8), null);
            }, objectMapper, properties);

            assertThatThrownBy(() -> provider.execute(request()))
                    .as(code)
                    .isInstanceOfSatisfying(
                            com.rivell.workbench.intelligence.ProviderRateLimitedException.class,
                            failure -> assertThat(failure.retryAfter())
                                    .isEqualTo(Duration.ofSeconds(7)));
        }
    }

    @Test
    void theShortCircuitDoesNotBlockAnotherProviderIdentity() {
        var guard = new com.rivell.workbench.intelligence.ProviderCreditGuard(
                java.time.Clock.systemUTC());
        var generative = new OpenAiCompatibleGenerativeProvider(
                (path, payload) -> {
                    throw new org.springframework.web.client.RestClientResponseException(
                            "rejected", 429, "Too Many Requests", null,
                            "{\"error\":{\"code\":\"credit_balance_exhausted\"}}"
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8), null);
                },
                objectMapper, new GenerativeProperties(
                        "openai-compatible", "https://generation.test/v1", "key", "gpt", null, null),
                guard);
        var decision = new OpenAiCompatibleDecisionProvider(
                (path, payload) -> response("""
                        {"needs_review":{"answer":"no","confidence":0.9},
                         "position_1":{"answer":"WALK_AWAY","confidence":0.8}}"""),
                objectMapper, new GenerativeProperties(
                        "openai-compatible", "https://decision.test/v1", "key", "gpt", null, null),
                guard);

        assertThatThrownBy(() -> generative.generate(
                new com.rivell.workbench.intelligence.GenerationRequest(
                        "gpt", new SemanticState() {
                            @Override
                            public int schemaVersion() {
                                return 1;
                            }

                            @Override
                            public Object payload() {
                                return Map.of("text", "t");
                            }
                        }, new com.rivell.workbench.intelligence.ReasoningSpec(
                                "spec", 1, "p", 1, Map.of("field", "d")))))
                .isInstanceOf(com.rivell.workbench.intelligence.ProviderOutOfCreditException.class);

        assertThat(decision.execute(request()).answers()).isNotEmpty();
    }

    private static final class TestClock extends java.time.Clock {

        private java.time.Instant instant = java.time.Instant.parse("2026-10-24T10:00:00Z");

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public java.time.Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public java.time.Instant instant() {
            return instant;
        }
    }
}
