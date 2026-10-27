package com.rivell.workbench.intelligence.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rivell.workbench.intelligence.GenerationRequest;
import com.rivell.workbench.intelligence.GenerationResult;
import com.rivell.workbench.intelligence.ProviderProtocolException;
import com.rivell.workbench.intelligence.ProviderRejectedException;
import com.rivell.workbench.intelligence.ProviderUnavailableException;
import com.rivell.workbench.intelligence.ReasoningSpec;
import com.rivell.workbench.intelligence.SemanticState;
import com.rivell.workbench.intelligence.SpecStateMismatchException;
import com.rivell.workbench.intelligence.GenerativeReasoningService;
import com.rivell.workbench.intelligence.GenerativeProviderRegistry;
import com.rivell.workbench.intelligence.ModelBinding;
import com.rivell.workbench.intelligence.ModelPolicy;
import com.rivell.workbench.intelligence.NoProviderConfiguredException;

class OpenAiCompatibleGenerativeProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ReasoningSpec capSpec() {
        return new ReasoningSpec("liability-cap-extraction", 1, "Extract cap facts.",
                1, new LinkedHashMap<>(java.util.Map.of(
                        "liabilityCapMultiple", "numeric multiple",
                        "capBasis", "cap basis")));
    }

    private static SemanticState state(String text) {
        return new SemanticState() {

            @Override
            public int schemaVersion() {
                return 1;
            }

            @Override
            public Object payload() {
                return java.util.Map.of("text", text);
            }
        };
    }

    private static String chatResponse(String content, String finishReason, String refusal) {
        return """
                {"model": "gpt-test", "choices": [{"index": 0,
                  "message": {"role": "assistant", "content": %s, "refusal": %s},
                  "finish_reason": "%s"}],
                 "usage": {"prompt_tokens": 90, "completion_tokens": 10}}
                """.formatted(
                content == null ? "null" : MAPPER.valueToTree(content).toString(),
                refusal == null ? "null" : "\"" + refusal + "\"",
                finishReason == null ? "stop" : finishReason);
    }

    @Test
    void parsesStrictJsonContentIntoTypedFields() {
        var accessor = scripted(request -> {
            assertThat(request).contains("liabilityCapMultiple").contains("\"strict\":true")
                    .doesNotContain("Bearer");
            return chatResponse(
                    "{\"liabilityCapMultiple\":\"3\",\"capBasis\":\"PRECEDING_12_MONTH_FEES\"}",
                    "stop", null);
        });
        var provider = new OpenAiCompatibleGenerativeProvider(
                accessor, MAPPER, new GenerativeProperties(
                        "openai-compatible", "https://gateway.test/v1", "key", "gpt-test",
                        null, null));

        var result = provider.generate(new GenerationRequest(
                "cap-model", state("cap text"), capSpec()));

        assertThat(result.fields())
                .containsEntry("liabilityCapMultiple", "3")
                .containsEntry("capBasis", "PRECEDING_12_MONTH_FEES");
        assertThat(result.resolvedModel()).isEqualTo("gpt-test");
    }

    @Test
    void configuredReasoningEffortIsSentAndUnsetIsOmitted() {
        var sent = new java.util.ArrayList<String>();
        var accessor = scripted(request -> {
            sent.add(request);
            return chatResponse(
                    "{\"liabilityCapMultiple\":\"3\",\"capBasis\":\"PRECEDING_12_MONTH_FEES\"}",
                    "stop", null);
        });
        var reasoningOff = new OpenAiCompatibleGenerativeProvider(accessor, MAPPER,
                new GenerativeProperties("openai-compatible", "https://gateway.test/v1", "key",
                        "gpt-test", null, null, null, null, null,
                        new GenerativeProperties.Reasoning(false, null)));
        var unset = new OpenAiCompatibleGenerativeProvider(accessor, MAPPER,
                new GenerativeProperties("openai-compatible", "https://gateway.test/v1", "key",
                        "gpt-test", null, null));

        reasoningOff.generate(new GenerationRequest("cap-model", state("cap text"), capSpec()));
        unset.generate(new GenerationRequest("cap-model", state("cap text"), capSpec()));

        assertThat(sent.get(0)).contains("\"reasoning_effort\":\"none\"");
        assertThat(sent.get(1)).doesNotContain("reasoning_effort");
    }

    @Test
    void aLongRedraftedClauseIsAcceptedWhileAnOverlongFieldIsRejected() {
        var spec = new ReasoningSpec("m7-open-ended-position", 2, "Redraft the clause.", 2,
                new LinkedHashMap<>(Map.of("proposedLanguage", "new clause text")));
        var longClause = "The Supplier shall keep the Customer's data confidential. ".repeat(60);
        var provider = new OpenAiCompatibleGenerativeProvider(
                scripted(request -> chatResponse(
                        "{\"proposedLanguage\":\"" + longClause + "\"}", "stop", null)),
                MAPPER, new GenerativeProperties(
                        "openai-compatible", "https://gateway.test/v1", "key", "gpt", null, null));
        var state = new com.rivell.workbench.intelligence.SemanticState() {
            @Override
            public int schemaVersion() {
                return 2;
            }

            @Override
            public Object payload() {
                return Map.of("clauseText", "x");
            }
        };

        assertThat(longClause.length()).isGreaterThan(2000);
        assertThat(provider.generate(new GenerationRequest("gpt", state, spec)).fields())
                .containsEntry("proposedLanguage", longClause);

        var overlong = new OpenAiCompatibleGenerativeProvider(
                scripted(request -> chatResponse(
                        "{\"proposedLanguage\":\"" + "x".repeat(8001) + "\"}", "stop", null)),
                MAPPER, new GenerativeProperties(
                        "openai-compatible", "https://gateway.test/v1", "key", "gpt", null, null));
        assertThatThrownBy(() -> overlong.generate(new GenerationRequest("gpt", state, spec)))
                .isInstanceOf(com.rivell.workbench.intelligence.ProviderProtocolException.class);
    }

    @Test
    void strictSchemaWireShapeIsOpenAiCompliant() throws Exception {
        var spec = new ReasoningSpec(
                "liability-cap-extraction", 1, "Extract cap facts.", 1,
                new LinkedHashMap<>(Map.of("capBasis", "cap basis")),
                Map.of("capBasis", java.util.Set.of("PRECEDING_12_MONTH_FEES", "ANNUAL_FEES")));
        var requests = new java.util.ArrayList<String>();
        var provider = new OpenAiCompatibleGenerativeProvider(
                scripted(request -> {
                    requests.add(request);
                    return chatResponse("{\"capBasis\":\"ANNUAL_FEES\"}", "stop", null);
                }),
                MAPPER, new GenerativeProperties(
                        "openai-compatible", "https://gateway.test/v1", "key", "gpt",
                        null, null));

        provider.generate(new GenerationRequest("cap-model", state("t"), spec));

        var request = MAPPER.readTree(requests.get(0));
        var responseFormat = request.path("response_format");
        assertThat(responseFormat.path("type").asText()).isEqualTo("json_schema");
        var jsonSchema = responseFormat.path("json_schema");
        assertThat(jsonSchema.path("name").asText()).isEqualTo("liability_cap_extraction");
        assertThat(jsonSchema.path("strict").asBoolean()).isTrue();
        assertThat(jsonSchema.path("schema").path("additionalProperties").asBoolean()).isFalse();
        assertThat(jsonSchema.path("schema").path("required").toString())
                .contains("capBasis");
        var capProperty = jsonSchema.path("schema").path("properties").path("capBasis");
        assertThat(capProperty.path("type").asText()).isEqualTo("string");
        assertThat(capProperty.path("enum").toString())
                .contains("PRECEDING_12_MONTH_FEES", "ANNUAL_FEES");
        // the M5 defect regression guard: camelCase wire keys must never reappear
        assertThat(requests.get(0))
                .doesNotContain("responseFormat").doesNotContain("jsonSchema")
                .doesNotContain("Bearer");
        assertThat(requests.get(0)).contains("must be exactly one of");
    }

    @Test
    void jsonObjectModeSendsJsonObjectTypeWithoutSchema() throws Exception {
        var requests = new java.util.ArrayList<String>();
        var provider = new OpenAiCompatibleGenerativeProvider(
                scripted(request -> {
                    requests.add(request);
                    return chatResponse(
                            "{\"liabilityCapMultiple\":\"3\",\"capBasis\":\"ANNUAL_FEES\"}",
                            "stop", null);
                }),
                MAPPER, new GenerativeProperties(
                        "openai-compatible", "https://gateway.test/v1", "key", "gpt",
                        GenerativeProperties.RESPONSE_MODE_JSON_OBJECT, null, null, null));

        var result = provider.generate(new GenerationRequest(
                "cap-model", state("t"), capSpec()));

        var request = MAPPER.readTree(requests.get(0));
        assertThat(request.path("response_format").path("type").asText())
                .isEqualTo("json_object");
        assertThat(request.path("response_format").has("json_schema")).isFalse();
        assertThat(result.fields()).containsEntry("capBasis", "ANNUAL_FEES");
    }

    @Test
    void fencedJsonWithProseIsToleratedThenValidated() {
        var provider = new OpenAiCompatibleGenerativeProvider(
                scripted(request -> chatResponse(
                        "```json\n"
                                + "{\"liabilityCapMultiple\":\"3\",\"capBasis\":\"ANNUAL_FEES\"}\n"
                                + "```\nHope that helps.",
                        "stop", null)),
                MAPPER, new GenerativeProperties(
                        "openai-compatible", "https://gateway.test/v1", "key", "gpt", null, null));

        var result = provider.generate(new GenerationRequest(
                "cap-model", state("t"), capSpec()));
        assertThat(result.fields()).containsEntry("capBasis", "ANNUAL_FEES");
    }

    @Test
    void singleLineFencedJsonIsToleratedThenValidated() {
        var provider = new OpenAiCompatibleGenerativeProvider(
                scripted(request -> chatResponse(
                        "```json {\"liabilityCapMultiple\":\"3\",\"capBasis\":\"ANNUAL_FEES\"}```",
                        "stop", null)),
                MAPPER, new GenerativeProperties(
                        "openai-compatible", "https://gateway.test/v1", "key", "gpt", null, null));

        var result = provider.generate(new GenerationRequest(
                "cap-model", state("t"), capSpec()));
        assertThat(result.fields()).containsEntry("liabilityCapMultiple", "3");
    }

    @Test
    void nonJsonProseStillFailsClosed() {
        var provider = new OpenAiCompatibleGenerativeProvider(
                scripted(request -> chatResponse(
                        "The liability cap is three times the fees.", "stop", null)),
                MAPPER, new GenerativeProperties(
                        "openai-compatible", "https://gateway.test/v1", "key", "gpt", null, null));

        assertThatThrownBy(() -> provider.generate(
                new GenerationRequest("cap-model", state("t"), capSpec())))
                .isInstanceOf(ProviderProtocolException.class);
    }

    @Test
    void arraysJoinOnlyForDeclaredListFields() {
        var spec = new ReasoningSpec(
                "evidence-ids", 1, "Extract evidence ids.", 1,
                new LinkedHashMap<>(Map.of(
                        "liabilityCapMultiple", "numeric multiple", "evidenceIds", "evidence ids")),
                Map.of(), java.util.Set.of("evidenceIds"));
        var provider = new OpenAiCompatibleGenerativeProvider(
                scripted(request -> chatResponse(
                        "{\"liabilityCapMultiple\":\"3\",\"evidenceIds\":[\"EV1\",\"EV2\"]}",
                        "stop", null)),
                MAPPER, new GenerativeProperties(
                        "openai-compatible", "https://gateway.test/v1", "key", "gpt", null, null));

        var result = provider.generate(new GenerationRequest("cap-model", state("t"), spec));
        assertThat(result.fields()).containsEntry("evidenceIds", "EV1,EV2");
    }

    @Test
    void arraysOnUndeclaredFieldsFailClosed() {
        var provider = new OpenAiCompatibleGenerativeProvider(
                scripted(request -> chatResponse(
                        "{\"liabilityCapMultiple\":\"3\",\"capBasis\":[\"ANNUAL_FEES\"]}",
                        "stop", null)),
                MAPPER, new GenerativeProperties(
                        "openai-compatible", "https://gateway.test/v1", "key", "gpt", null, null));

        assertThatThrownBy(() -> provider.generate(
                new GenerationRequest("cap-model", state("t"), capSpec())))
                .isInstanceOf(ProviderProtocolException.class);
    }

    @Test
    void errorBodySurfacesTypedProviderFailureWithDiagnostics() {
        var provider = new OpenAiCompatibleGenerativeProvider(
                scripted(request -> "{\"error\":{\"type\":\"rate_limit\",\"code\":\"42\"}}"),
                MAPPER, new GenerativeProperties(
                        "openai-compatible", "https://gateway.test/v1", "key", "gpt", null, null));

        assertThatThrownBy(() -> provider.generate(
                new GenerationRequest("cap-model", state("t"), capSpec())))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessageContaining("rate_limit").hasMessageContaining("42");
    }

    @Test
    void anInvalidApiKeyInA200IsRejectedAndNotRetried() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var provider = new OpenAiCompatibleGenerativeProvider(
                scripted(request -> {
                    calls.incrementAndGet();
                    return "{\"error\":{\"code\":\"invalid_api_key\","
                            + "\"type\":\"invalid_request_error\"}}";
                }),
                MAPPER, new GenerativeProperties(
                        "openai-compatible", "https://gateway.test/v1", "key", "gpt", null, null));
        var service = new GenerativeReasoningService(
                asset -> new ModelBinding("openai-compatible", "gpt-test"),
                (GenerativeProviderRegistry) id -> Optional.of(provider),
                Clock.systemUTC(),
                new com.rivell.workbench.intelligence.ProviderRetry(
                        java.util.List.of(java.time.Duration.ZERO, java.time.Duration.ZERO)));

        assertThatThrownBy(() -> service.generate(capSpec(), state("t")))
                .isExactlyInstanceOf(ProviderRejectedException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    void allowedValuesRejectOutsideEnumValues() {
        var spec = new ReasoningSpec(
                "cap-spec", 1, "Extract cap facts.", 1,
                new LinkedHashMap<>(Map.of("liabilityCapMultiple", "numeric multiple", "capBasis", "cap basis")),
                Map.of("capBasis", java.util.Set.of("PRECEDING_12_MONTH_FEES", "ANNUAL_FEES")));
        var accessor = scripted(request -> chatResponse(
                "{\"liabilityCapMultiple\":\"3\",\"capBasis\":\"UNLIMITED\"}",
                "stop", null));
        var provider = new OpenAiCompatibleGenerativeProvider(
                accessor, MAPPER, new GenerativeProperties(
                        "openai-compatible", "https://gateway.test/v1", "key", "gpt-test",
                        null, null));
        var service = new GenerativeReasoningService(
                asset -> new ModelBinding("openai-compatible", "gpt-test"),
                (GenerativeProviderRegistry) id -> Optional.of(provider),
                Clock.systemUTC());

        assertThatThrownBy(() -> service.generate(spec, state("t")))
                .isInstanceOf(ProviderProtocolException.class)
                .hasMessageContaining("outside its allowed values");
    }

    private OpenAiCompatibleGenerativeProvider.RestClientAccessor scripted(
            java.util.function.Function<String, String> handler) {
        return (path, payload) -> handler.apply(payload);
    }

    @Test
    void refusalFailsClosed() {
        var accessor = scripted(request -> chatResponse(null, "stop", "cannot help"));
        var provider = new OpenAiCompatibleGenerativeProvider(
                accessor, MAPPER, new GenerativeProperties(
                        "openai-compatible", "https://gateway.test/v1", "key", "m", null, null));

        assertThatThrownBy(() -> provider.generate(
                new GenerationRequest("cap-model", state("t"), capSpec())))
                .isInstanceOf(ProviderRejectedException.class);
    }

    @Test
    void truncatedOutputFailsClosed() {
        var accessor = scripted(request -> chatResponse(
                "{\"liabilityCapMultiple\":\"3\"", "length", null));
        var provider = new OpenAiCompatibleGenerativeProvider(
                accessor, MAPPER, new GenerativeProperties(
                        "openai-compatible", "https://gateway.test/v1", "key", "gpt", null, null));

        assertThatThrownBy(() -> provider.generate(
                new GenerationRequest("cap-model", state("t"), capSpec())))
                .isInstanceOf(ProviderProtocolException.class);
    }

    @Test
    void unknownExtraFieldFailsClosed() {
        var accessor = scripted(request -> chatResponse(
                "{\"liabilityCapMultiple\":\"3\",\"capBasis\":\"ANNUAL_FEES\",\"extra\":\"x\"}",
                "stop", null));
        var provider = new OpenAiCompatibleGenerativeProvider(
                accessor, MAPPER, new GenerativeProperties(
                        "openai-compatible", "https://gateway.test/v1", "key", "gpt", null, null));

        assertThatThrownBy(() -> provider.generate(
                new GenerationRequest("cap-model", state("t"), capSpec())))
                .isInstanceOf(ProviderProtocolException.class);
    }

    @Test
    void httpStatusesMapToHonestFailures() {
        var provider = new OpenAiCompatibleGenerativeProvider(
                scripted(request -> {
                    throw new org.springframework.web.client.RestClientResponseException(
                            "rejected", 429, "limited", null, null, null);
                }),
                MAPPER, new GenerativeProperties(
                        "openai-compatible", "https://gateway.test/v1", "key", "gpt", null, null));

        assertThatThrownBy(() -> provider.generate(
                new GenerationRequest("cap-model", state("t"), capSpec())))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    void anAccountWithNoCreditsIsRejectedNotRetried() {
        var body = "{\"error\":{\"message\":\"You exceeded your current quota\",\"type\":\"insufficient_quota\","
                + "\"code\":\"insufficient_quota\"}}";
        var provider = new OpenAiCompatibleGenerativeProvider(
                scripted(request -> {
                    throw new org.springframework.web.client.RestClientResponseException(
                            "rejected", 429, "Too Many Requests", null,
                            body.getBytes(java.nio.charset.StandardCharsets.UTF_8), null);
                }),
                MAPPER, new GenerativeProperties(
                        "openai-compatible", "https://gateway.test/v1", "key", "gpt", null, null));

        assertThatThrownBy(() -> provider.generate(
                new GenerationRequest("cap-model", state("t"), capSpec())))
                .isInstanceOf(com.rivell.workbench.intelligence.ProviderOutOfCreditException.class)
                .hasMessageContaining("no credits");
    }

    @Test
    void everyDocumentedBillingCodeIsOutOfCredit() {
        for (var code : java.util.List.of("credit_balance_exhausted",
                "organization_spend_limit_exceeded", "project_spend_limit_exceeded",
                "organization_usage_limit_exceeded")) {
            var body = "{\"error\":{\"code\":\"" + code + "\",\"type\":\"invalid_request_error\"}}";
            var provider = new OpenAiCompatibleGenerativeProvider(
                    scripted(request -> {
                        throw new org.springframework.web.client.RestClientResponseException(
                                "rejected", 429, "Too Many Requests", null,
                                body.getBytes(java.nio.charset.StandardCharsets.UTF_8), null);
                    }),
                    MAPPER, new GenerativeProperties(
                            "openai-compatible", "https://gateway.test/v1", "key", "gpt", null, null));

            assertThatThrownBy(() -> provider.generate(
                    new GenerationRequest("cap-model", state("t"), capSpec())))
                    .as(code)
                    .isInstanceOf(com.rivell.workbench.intelligence.ProviderOutOfCreditException.class);
        }
    }

    @Test
    void aSpendLimitInTheTypeFieldIsOutOfCredit() {
        var body = "{\"error\":{\"type\":\"insufficient_quota\"}}";
        var provider = new OpenAiCompatibleGenerativeProvider(
                scripted(request -> {
                    throw new org.springframework.web.client.RestClientResponseException(
                            "rejected", 429, "Too Many Requests", null,
                            body.getBytes(java.nio.charset.StandardCharsets.UTF_8), null);
                }),
                MAPPER, new GenerativeProperties(
                        "openai-compatible", "https://gateway.test/v1", "key", "gpt", null, null));

        assertThatThrownBy(() -> provider.generate(
                new GenerationRequest("cap-model", state("t"), capSpec())))
                .isInstanceOf(com.rivell.workbench.intelligence.ProviderOutOfCreditException.class);
    }

    @Test
    void slowDownAndAPlain429CarryTheRetryAfter() {
        for (var body : java.util.List.of(
                "{\"error\":{\"code\":\"slow_down\",\"type\":\"rate_limit_error\"}}",
                "{\"error\":{\"code\":\"rate_limit_exceeded\",\"type\":\"rate_limit_error\"}}")) {
            var headers = new org.springframework.http.HttpHeaders();
            headers.set(org.springframework.http.HttpHeaders.RETRY_AFTER, "7");
            var provider = new OpenAiCompatibleGenerativeProvider(
                    scripted(request -> {
                        throw new org.springframework.web.client.RestClientResponseException(
                                "rejected", 429, "Too Many Requests", headers,
                                body.getBytes(java.nio.charset.StandardCharsets.UTF_8), null);
                    }),
                    MAPPER, new GenerativeProperties(
                            "openai-compatible", "https://gateway.test/v1", "key", "gpt", null, null));

            assertThatThrownBy(() -> provider.generate(
                    new GenerationRequest("cap-model", state("t"), capSpec())))
                    .as(body)
                    .isInstanceOfSatisfying(
                            com.rivell.workbench.intelligence.ProviderRateLimitedException.class,
                            failure -> assertThat(failure.retryAfter())
                                    .isEqualTo(java.time.Duration.ofSeconds(7)));
        }
    }

    @Test
    void outOfCreditShortCircuitsTheSameProviderForAMinute() {
        var clock = new TestClock();
        var guard = new com.rivell.workbench.intelligence.ProviderCreditGuard(clock);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var billing = "{\"error\":{\"code\":\"credit_balance_exhausted\"}}";
        var provider = new OpenAiCompatibleGenerativeProvider(
                scripted(request -> {
                    calls.incrementAndGet();
                    throw new org.springframework.web.client.RestClientResponseException(
                            "rejected", 429, "Too Many Requests", null,
                            billing.getBytes(java.nio.charset.StandardCharsets.UTF_8), null);
                }),
                MAPPER, new GenerativeProperties(
                        "openai-compatible", "https://gateway.test/v1", "key", "gpt", null, null),
                guard);

        assertThatThrownBy(() -> provider.generate(
                new GenerationRequest("cap-model", state("t"), capSpec())))
                .isInstanceOf(com.rivell.workbench.intelligence.ProviderOutOfCreditException.class);
        assertThat(calls).hasValue(1);

        assertThatThrownBy(() -> provider.generate(
                new GenerationRequest("cap-model", state("t"), capSpec())))
                .isInstanceOf(com.rivell.workbench.intelligence.ProviderOutOfCreditException.class);
        assertThat(calls).hasValue(1);

        clock.advance(java.time.Duration.ofSeconds(61));
        assertThatThrownBy(() -> provider.generate(
                new GenerationRequest("cap-model", state("t"), capSpec())))
                .isInstanceOf(com.rivell.workbench.intelligence.ProviderOutOfCreditException.class);
        assertThat(calls).hasValue(2);
    }

    @Test
    void aLongReadCallUsesTheLongRestClient() {
        var shortCalls = new java.util.concurrent.atomic.AtomicInteger();
        var longCalls = new java.util.concurrent.atomic.AtomicInteger();
        var body = chatResponse(
                "{\"liabilityCapMultiple\":\"3\",\"capBasis\":\"ANNUAL_FEES\"}", "stop", null);
        var provider = new OpenAiCompatibleGenerativeProvider(
                (path, payload) -> {
                    shortCalls.incrementAndGet();
                    return body;
                },
                (path, payload) -> {
                    longCalls.incrementAndGet();
                    return body;
                },
                MAPPER, new GenerativeProperties(
                        "openai-compatible", "https://gateway.test/v1", "key", "gpt", null, null),
                new com.rivell.workbench.intelligence.ProviderCreditGuard(
                        java.time.Clock.systemUTC()));

        provider.generate(new GenerationRequest("cap-model", state("t"), capSpec()));
        provider.generate(new GenerationRequest("cap-model", state("t"), capSpec(), true));

        assertThat(shortCalls).hasValue(1);
        assertThat(longCalls).hasValue(1);
    }

    private static final class TestClock extends java.time.Clock {

        private java.time.Instant instant = java.time.Instant.parse("2026-10-24T10:00:00Z");

        void advance(java.time.Duration duration) {
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

    @Test
    void unconfiguredProviderFailsClosed() {
        var provider = new OpenAiCompatibleGenerativeProvider(
                scripted(request -> chatResponse("{}", "stop", null)),
                MAPPER, new GenerativeProperties(
                        "openai-compatible", "https://gateway.test/v1", "", "gpt", null, null));

        assertThatThrownBy(() -> provider.generate(
                new GenerationRequest("cap-model", state("t"), capSpec())))
                .isInstanceOf(NoProviderConfiguredException.class);
    }

    @Test
    void serviceRoutesThroughRegistryAndValidatesSpecState() {
        var provider = new OpenAiCompatibleGenerativeProvider(
                scripted(request -> chatResponse(
                        "{\"liabilityCapMultiple\":\"3\",\"capBasis\":\"PRECEDING_12_MONTH_FEES\"}",
                        "stop", null)),
                MAPPER, new GenerativeProperties(
                        "openai-compatible", "https://gateway.test/v1", "key", "gpt", null, null));
        var registry = (GenerativeProviderRegistry) id ->
                id.equals("openai-compatible")
                        ? java.util.Optional.of(provider)
                        : java.util.Optional.empty();
        var service = new GenerativeReasoningService(
                (asset) -> new ModelBinding("openai-compatible", "cap-model"), registry,
                java.time.Clock.systemUTC());

        var execution = service.generate(capSpec(), state("three times the fees"));
        assertThat(execution.fields()).containsEntry("liabilityCapMultiple", "3");

        assertThatThrownBy(() -> service.generate(
                new ReasoningSpec("other", 1, "p", 7, java.util.Map.of("f", "d")), state("t")))
                .isInstanceOf(SpecStateMismatchException.class);
    }
}
