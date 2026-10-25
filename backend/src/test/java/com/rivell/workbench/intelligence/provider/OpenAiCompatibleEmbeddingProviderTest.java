package com.rivell.workbench.intelligence.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rivell.workbench.intelligence.EmbeddingRequest;
import com.rivell.workbench.intelligence.ProviderCreditGuard;
import com.rivell.workbench.intelligence.ProviderOutOfCreditException;
import com.rivell.workbench.intelligence.ProviderRateLimitedException;
import com.rivell.workbench.intelligence.ProviderRejectedException;

class OpenAiCompatibleEmbeddingProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static EmbeddingProperties properties() {
        return new EmbeddingProperties("openai-compatible", "https://gateway.test/v1", "key",
                "embed-test", 2, 64, Duration.ofSeconds(1), Duration.ofSeconds(1));
    }

    private static String responseFor(int count) {
        var data = new StringBuilder();
        for (int index = 0; index < count; index++) {
            if (index > 0) {
                data.append(',');
            }
            data.append("{\"index\":").append(index)
                    .append(",\"embedding\":[0.1,0.2]}");
        }
        return "{\"model\":\"embed-test-2026\",\"data\":[" + data + "],"
                + "\"usage\":{\"prompt_tokens\":" + count + "}}";
    }

    private static List<String> inputs(String payload) {
        var values = new ArrayList<String>();
        try {
            MAPPER.readTree(payload).path("input").forEach(node -> values.add(node.asText()));
        } catch (Exception e) {
            throw new IllegalArgumentException("unreadable request", e);
        }
        return values;
    }

    @Test
    void aRejectedBatchIsSplitUntilTheFailingInputIsIsolated() {
        var posted = new ArrayList<List<String>>();
        var embedded = new ArrayList<List<String>>();
        var provider = new OpenAiCompatibleEmbeddingProvider((path, payload) -> {
            var batch = inputs(payload);
            posted.add(batch);
            if (batch.contains("bad")) {
                throw new org.springframework.web.client.RestClientResponseException(
                        "bad request", 400, "Bad Request", null,
                        "{\"error\":{\"code\":\"invalid_input\"}}"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8), null);
            }
            embedded.add(batch);
            return responseFor(batch.size());
        }, MAPPER, properties());

        assertThatThrownBy(() -> provider.embed(
                new EmbeddingRequest("embed-test", List.of("one", "bad", "two"), 2)))
                .isInstanceOf(ProviderRejectedException.class);

        assertThat(posted).containsExactly(
                List.of("one", "bad", "two"),
                List.of("one"),
                List.of("bad", "two"),
                List.of("bad"),
                List.of("two"));
        assertThat(embedded).containsExactly(List.of("one"), List.of("two"));
    }

    @Test
    void a413IsSplitTheSameWay() {
        var posted = new ArrayList<List<String>>();
        var provider = new OpenAiCompatibleEmbeddingProvider((path, payload) -> {
            var batch = inputs(payload);
            posted.add(batch);
            if (batch.size() > 1) {
                throw new org.springframework.web.client.RestClientResponseException(
                        "too large", 413, "Payload Too Large", null, null, null);
            }
            return responseFor(batch.size());
        }, MAPPER, properties());

        assertThat(provider.embed(
                new EmbeddingRequest("embed-test", List.of("a", "b", "c"), 2)).vectors())
                .hasSize(3);
        assertThat(posted).contains(List.of("a", "b", "c"), List.of("a"), List.of("b"),
                List.of("c"));
    }

    @Test
    void aGoodBatchReturnsOrderedVectors() {
        var provider = new OpenAiCompatibleEmbeddingProvider(
                (path, payload) -> responseFor(3), MAPPER, properties());

        var batch = provider.embed(
                new EmbeddingRequest("embed-test", List.of("a", "b", "c"), 2));

        assertThat(batch.vectors()).hasSize(3);
        assertThat(batch.vectors().get(2)).containsExactly(0.1f, 0.2f);
        assertThat(batch.usage().inputTokens()).isEqualTo(3);
    }

    @Test
    void everyBillingCodeIsOutOfCredit() {
        for (var code : List.of("credit_balance_exhausted", "organization_spend_limit_exceeded",
                "project_spend_limit_exceeded", "organization_usage_limit_exceeded")) {
            var body = "{\"error\":{\"code\":\"" + code + "\"}}";
            var provider = new OpenAiCompatibleEmbeddingProvider((path, payload) -> {
                throw new org.springframework.web.client.RestClientResponseException(
                        "rejected", 429, "Too Many Requests", null,
                        body.getBytes(java.nio.charset.StandardCharsets.UTF_8), null);
            }, MAPPER, properties());

            assertThatThrownBy(() -> provider.embed(
                    new EmbeddingRequest("embed-test", List.of("one"), 2)))
                    .as(code)
                    .isInstanceOf(ProviderOutOfCreditException.class);
        }
    }

    @Test
    void insufficientQuotaInTheTypeFieldIsOutOfCredit() {
        var provider = new OpenAiCompatibleEmbeddingProvider((path, payload) -> {
            throw new org.springframework.web.client.RestClientResponseException(
                    "rejected", 429, "Too Many Requests", null,
                    "{\"error\":{\"type\":\"insufficient_quota\"}}"
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8), null);
        }, MAPPER, properties());

        assertThatThrownBy(() -> provider.embed(
                new EmbeddingRequest("embed-test", List.of("one"), 2)))
                .isInstanceOf(ProviderOutOfCreditException.class);
    }

    @Test
    void slowDownCarriesRetryAfter() {
        var headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "7");
        var provider = new OpenAiCompatibleEmbeddingProvider((path, payload) -> {
            throw new org.springframework.web.client.RestClientResponseException(
                    "rejected", 429, "Too Many Requests", headers,
                    "{\"error\":{\"code\":\"slow_down\"}}"
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8), null);
        }, MAPPER, properties());

        assertThatThrownBy(() -> provider.embed(
                new EmbeddingRequest("embed-test", List.of("one"), 2)))
                .isInstanceOfSatisfying(ProviderRateLimitedException.class,
                        failure -> assertThat(failure.retryAfter()).isEqualTo(Duration.ofSeconds(7)));
    }

    @Test
    void outOfCreditShortCircuitsOnlyTheSameProviderIdentity() {
        var clock = new TestClock();
        var guard = new ProviderCreditGuard(clock);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var provider = new OpenAiCompatibleEmbeddingProvider((path, payload) -> {
            calls.incrementAndGet();
            throw new org.springframework.web.client.RestClientResponseException(
                    "rejected", 429, "Too Many Requests", null,
                    "{\"error\":{\"code\":\"credit_balance_exhausted\"}}"
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8), null);
        }, MAPPER, properties(), guard);

        assertThatThrownBy(() -> provider.embed(
                new EmbeddingRequest("embed-test", List.of("one"), 2)))
                .isInstanceOf(ProviderOutOfCreditException.class);
        assertThatThrownBy(() -> provider.embed(
                new EmbeddingRequest("embed-test", List.of("one"), 2)))
                .isInstanceOf(ProviderOutOfCreditException.class);
        assertThat(calls).hasValue(1);

        clock.advance(Duration.ofSeconds(61));
        assertThatThrownBy(() -> provider.embed(
                new EmbeddingRequest("embed-test", List.of("one"), 2)))
                .isInstanceOf(ProviderOutOfCreditException.class);
        assertThat(calls).hasValue(2);
    }

    @Test
    void aConfiguredBatchLargerThanTheProviderLimitFailsAtStartup() {
        assertThatThrownBy(() -> new EmbeddingProperties("openai-compatible", null, "key",
                "embed-test", 2, 2049, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2048");
    }

    @Test
    void tokenEstimatePartitionsABatchEvenWhenTheInputCountAllowsMore() {
        var big = "x".repeat(800_000);
        var batches = OpenAiCompatibleEmbeddingProvider.partition(
                List.of("small", big, "small"), 64);

        assertThat(batches).hasSize(3);
        assertThat(batches.get(1)).containsExactly(big);
    }

    @Test
    void aGenerativeOutOfCreditDoesNotBlockEmbeddingsOnTheSameGateway() {
        var guard = new ProviderCreditGuard(java.time.Clock.systemUTC());
        var sharedBase = "https://shared-gateway.test/v1";
        var generative = new OpenAiCompatibleGenerativeProvider((path, payload) -> {
            throw new org.springframework.web.client.RestClientResponseException(
                    "rejected", 429, "Too Many Requests", null,
                    "{\"error\":{\"code\":\"credit_balance_exhausted\"}}"
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8), null);
        }, MAPPER, new GenerativeProperties("openai-compatible", sharedBase, "generation-key",
                "gpt", null, null), guard);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> generative.generate(
                new com.rivell.workbench.intelligence.GenerationRequest("gpt",
                        new com.rivell.workbench.intelligence.SemanticState() {
                            @Override
                            public int schemaVersion() {
                                return 1;
                            }

                            @Override
                            public Object payload() {
                                return Map.of("text", "t");
                            }
                        },
                        new com.rivell.workbench.intelligence.ReasoningSpec(
                                "spec", 1, "p", 1, Map.of("field", "d")))))
                .isInstanceOf(ProviderOutOfCreditException.class);

        var embeddings = new OpenAiCompatibleEmbeddingProvider(
                (path, payload) -> responseFor(1), MAPPER,
                new EmbeddingProperties("openai-compatible", sharedBase, "embedding-key",
                        "embed-test", 2, 64, Duration.ofSeconds(1), Duration.ofSeconds(1)),
                guard);

        assertThat(embeddings.embed(
                new EmbeddingRequest("embed-test", List.of("one"), 2)).vectors()).hasSize(1);
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
