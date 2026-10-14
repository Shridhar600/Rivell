package com.rivell.workbench.intelligence.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rivell.workbench.intelligence.ChoiceQuestion;
import com.rivell.workbench.intelligence.DecisionExecutionRequest;
import com.rivell.workbench.intelligence.NoProviderConfiguredException;
import com.rivell.workbench.intelligence.ProviderProtocolException;
import com.rivell.workbench.intelligence.ProviderRejectedException;
import com.rivell.workbench.intelligence.ProviderTimeoutException;
import com.rivell.workbench.intelligence.ProviderUnavailableException;
import com.rivell.workbench.intelligence.ProviderExecutionResult;
import com.rivell.workbench.intelligence.QuestionPack;
import com.rivell.workbench.intelligence.RatingQuestion;
import com.rivell.workbench.intelligence.SemanticState;
import com.rivell.workbench.intelligence.YesNoQuestion;

/**
 * Jev adapter contract tests against an in-JVM HTTP mock serving the documented
 * TypeSafe shapes. No network access; no real credentials.
 */
class JevProviderContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JevProperties properties(int port, Duration readTimeout) {
        return new JevProperties("http://127.0.0.1:" + port, "test-key-not-real",
                "jev-latest", Duration.ofMillis(200), readTimeout);
    }

    private static DecisionExecutionRequest request(QuestionPack pack) {
        return new DecisionExecutionRequest("jev-latest", state(), pack);
    }

    private static SemanticState state() {
        return new SemanticState() {
            @Override
            public int schemaVersion() {
                return 1;
            }

            @Override
            public Object payload() {
                return Map.of("text", "synthetic clause text");
            }
        };
    }

    @Test
    void mapsMixedPrimitivesAndCapturesResolvedModelAndUsage() throws Exception {
        try (var server = new MockJevServer(new Response(200, successBody()))) {
            var provider = new JevProvider(properties(server.port(), Duration.ofSeconds(5)), MAPPER);

            var result = provider.execute(request(new QuestionPack(Map.of(
                    "mentions_confidentiality", new YesNoQuestion("Is this confidentiality?", null, null),
                    "clause_concept", new ChoiceQuestion("Which concept?", orderedCriteria()),
                    "specificity", new RatingQuestion("How specific?", java.util.List.of("vague", "specific", "detailed"))))));

            var body = MAPPER.readTree(server.lastRequestBody());
            assertThat(body.get("model").asText()).isEqualTo("jev-latest");
            assertThat(body.get("state").get("text").asText()).isEqualTo("synthetic clause text");
            var questions = body.get("questions");
            assertThat(questions.get("mentions_confidentiality").get("type").asText()).isEqualTo("noul");
            assertThat(questions.get("clause_concept").get("type").asText()).isEqualTo("choice");
            assertThat(questions.get("clause_concept").get("criteria").get("confidentiality").asText())
                    .isEqualTo("Confidentiality obligation.");
            assertThat(questions.get("specificity").get("type").asText()).isEqualTo("score");
            assertThat(questions.get("specificity").get("criteria").get(1).asText()).isEqualTo("specific");
            assertThat(server.lastAuthHeader()).isEqualTo("Bearer test-key-not-real");

            assertThat(result.resolvedModel()).isEqualTo("jev-1.13.0");
            assertThat(result.usage()).isEqualTo(new com.rivell.workbench.intelligence.TokenUsage(120, 12));
            assertThat(result.answers()).hasSize(3);
        }
    }

    @Test
    void mapsValidationRejection() throws Exception {
        try (var server = new MockJevServer(new Response(422,
                "{\"detail\":[{\"loc\":[\"body\",\"state\"],\"msg\":\"Field required\",\"type\":\"missing\"}]}"))) {
            var provider = new JevProvider(properties(server.port(), Duration.ofSeconds(5)), MAPPER);

            assertThatThrownBy(() -> provider.execute(request(pack())))
                    .isInstanceOf(ProviderRejectedException.class);
        }
    }

    @Test
    void mapsAuthenticationRejection() throws Exception {
        try (var server = new MockJevServer(new Response(401, "{}"))) {
            var provider = new JevProvider(properties(server.port(), Duration.ofSeconds(5)), MAPPER);

            assertThatThrownBy(() -> provider.execute(request(pack())))
                    .isInstanceOf(ProviderRejectedException.class)
                    .hasMessageContaining("401");
        }
    }

    @Test
    void anAccountWithNoCreditsIsRejectedNotRetried() throws Exception {
        try (var server = new MockJevServer(new Response(402,
                "{\"detail\":{\"error_type\":\"billing_error\",\"message\":\"no available credits\"}}"))) {
            var provider = new JevProvider(properties(server.port(), Duration.ofSeconds(5)), MAPPER);

            assertThatThrownBy(() -> provider.execute(request(pack())))
                    .isInstanceOf(ProviderRejectedException.class)
                    .hasMessage("Provider account has no credits (HTTP 402)");
        }
    }

    @Test
    void mapsServerAndRateLimitFailuresAsUnavailable() throws Exception {
        for (int status : new int[] {429, 500, 503}) {
            try (var server = new MockJevServer(new Response(status, "{}"))) {
                var provider = new JevProvider(properties(server.port(), Duration.ofSeconds(5)), MAPPER);

                assertThatThrownBy(() -> provider.execute(request(pack())))
                        .isInstanceOf(ProviderUnavailableException.class)
                        .hasMessageContaining(String.valueOf(status));
            }
        }
    }

    @Test
    void mapsSlowResponseToTimeout() throws Exception {
        try (var server = new MockJevServer(new Response(200, "{}", Duration.ofMillis(500)))) {
            var provider = new JevProvider(
                    properties(server.port(), Duration.ofMillis(100)), MAPPER);

            assertThatThrownBy(() -> provider.execute(request(pack())))
                    .isInstanceOf(ProviderTimeoutException.class);
        }
    }

    @Test
    void mapsUnreachableProviderToUnavailable() throws Exception {
        var provider = new JevProvider(
                new JevProperties("http://127.0.0.1:1", "test-key-not-real", "jev-latest",
                        Duration.ofMillis(100), Duration.ofSeconds(2)),
                MAPPER);

        assertThatThrownBy(() -> provider.execute(request(pack())))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    void rejectsUnconfiguredProviderBeforeCallingProvider() {
        var provider = new JevProvider(
                new JevProperties("http://127.0.0.1:1", " ", "jev-latest",
                        Duration.ofSeconds(1), Duration.ofSeconds(2)),
                MAPPER);

        assertThatThrownBy(() -> provider.execute(request(pack())))
                .isInstanceOf(NoProviderConfiguredException.class);
    }

    @Test
    void rejectsMissingAnswer() throws Exception {
        var success = successBody().replace("\"specificity\":", "\"unused_key\":");
        try (var server = new MockJevServer(new Response(200, success))) {
            var provider = new JevProvider(properties(server.port(), Duration.ofSeconds(5)), MAPPER);

            assertThatThrownBy(() -> provider.execute(request(pack())))
                    .isInstanceOf(ProviderProtocolException.class)
                    .hasMessageContaining("missing answer specificity");
        }
    }

    @Test
    void rejectsWrongPrimitiveType() throws Exception {
        var success = successBody().replace("\"type\": \"noul\"", "\"type\": \"choice\"");
        try (var server = new MockJevServer(new Response(200, success))) {
            var provider = new JevProvider(properties(server.port(), Duration.ofSeconds(5)), MAPPER);

            assertThatThrownBy(() -> provider.execute(request(pack())))
                    .isInstanceOf(ProviderProtocolException.class)
                    .hasMessageContaining("primitive choice, expected noul");
        }
    }

    @Test
    void rejectsUnknownChoiceLabel() throws Exception {
        var success = successBody().replace("\"confidentiality\": 0.95", "\"surprise\": 0.95");
        try (var server = new MockJevServer(new Response(200, success))) {
            var provider = new JevProvider(properties(server.port(), Duration.ofSeconds(5)), MAPPER);

            assertThatThrownBy(() -> provider.execute(request(pack())))
                    .isInstanceOf(ProviderProtocolException.class)
                    .hasMessageContaining("unknown choice label surprise");
        }
    }

    @Test
    void rejectsMalformedProbability() throws Exception {
        var success = successBody().replace("\"noul\": 0.94", "\"noul\": 1.7");
        try (var server = new MockJevServer(new Response(200, success))) {
            var provider = new JevProvider(properties(server.port(), Duration.ofSeconds(5)), MAPPER);

            assertThatThrownBy(() -> provider.execute(request(pack())))
                    .isInstanceOf(ProviderProtocolException.class)
                    .hasMessageContaining("malformed probability");
        }
    }

    @Test
    void discoversModels() throws Exception {
        try (var server = new MockJevServer(new Response(200, successBody()))) {
            var provider = new JevProvider(properties(server.port(), Duration.ofSeconds(5)), MAPPER);

            var models = provider.listModels();

            assertThat(models).extracting(JevRequestDtos.ModelMetadata::name)
                    .containsExactly("jev-1.13.0");
        }
    }

    private static LinkedHashMap<String, String> orderedCriteria() {
        var criteria = new LinkedHashMap<String, String>();
        criteria.put("confidentiality", "Confidentiality obligation.");
        criteria.put("liability", "Damage limitation.");
        return criteria;
    }

    private static QuestionPack pack() {
        return new QuestionPack(Map.of(
                "mentions_confidentiality", new YesNoQuestion("Is this confidentiality?", null, null),
                "clause_concept", new ChoiceQuestion("Which concept?", orderedCriteria()),
                "specificity", new RatingQuestion("How specific?", java.util.List.of("vague", "specific", "detailed"))));
    }

    private static String successBody() {
        return """
                {
                  "model": "jev-1.13.0",
                  "answers": {
                    "mentions_confidentiality": {"type": "noul", "noul": 0.94},
                    "clause_concept": {"type": "choice", "choice": "confidentiality", "confidence": 0.9,
                        "probabilities": {"confidentiality": 0.95, "liability": 0.05}},
                    "specificity": {"type": "score", "score": 1.43, "confidence": 0.9,
                        "legend": {"0": "vague", "1": "specific", "2": "detailed"},
                        "probabilities": {"0": 0.1, "1": 0.57, "2": 0.43}}
                  },
                  "usage": {"input_tokens": 120, "output_tokens": 12}
                }
                """;
    }

    static final class MockJevServer implements AutoCloseable {

        private final HttpServer server;
        private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
        private final AtomicReference<String> lastAuthHeader = new AtomicReference<>();

        MockJevServer(Response response) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/systemone", exchange -> {
                if (response.delay() != null) {
                    try {
                        Thread.sleep(response.delay().toMillis());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                lastRequestBody.set(new String(exchange.getRequestBody().readAllBytes()));
                lastAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                byte[] body = body(response);
                exchange.sendResponseHeaders(response.status(), body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.createContext("/v1/models", exchange -> {
                byte[] body = MAPPER.writeValueAsBytes(Map.of("models", java.util.List.of(Map.of(
                        "name", "jev-1.13.0", "description", "General-purpose system one model.",
                        "release_date", "2026-09-15"))));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
        }

        private static byte[] body(Response response) {
            return response.body().getBytes();
        }

        String lastRequestBody() {
            return lastRequestBody.get();
        }

        String lastAuthHeader() {
            return lastAuthHeader.get();
        }

        int port() {
            return server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    record Response(int status, String body, Duration delay) {

        Response(int status, String body) {
            this(status, body, null);
        }
    }
}
