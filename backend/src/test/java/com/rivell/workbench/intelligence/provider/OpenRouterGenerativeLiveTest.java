package com.rivell.workbench.intelligence.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rivell.workbench.intelligence.GenerativeReasoningService;
import com.rivell.workbench.intelligence.GenerativeReasoningService.GenerativeExecution;
import com.rivell.workbench.intelligence.GenerativeProviderRegistry;
import com.rivell.workbench.intelligence.ModelBinding;
import com.rivell.workbench.intelligence.ModelPolicy;
import com.rivell.workbench.intelligence.ProviderUnavailableException;
import com.rivell.workbench.intelligence.ReasoningSpec;
import com.rivell.workbench.intelligence.SemanticState;

/**
 * DW-006 live generative verification against a real remote model (OpenRouter
 * stealth/space-bunny-alpha, JSON mode) through the REAL chain:
 * GenerativeReasoningService → ModelPolicy → provider registry →
 * OpenAI-compatible adapter → schema/domain validation → typed result.
 * Opt-in only (OPENROUTER_LIVE=1 with OPENROUTER_API_KEY in the environment;
 * the key is never printed or logged). Synthetic fixture only. Engineering
 * observation, NOT a quality claim.
 */
@EnabledIfEnvironmentVariable(named = "OPENROUTER_LIVE", matches = "1")
class OpenRouterGenerativeLiveTest {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory
            .getLogger(OpenRouterGenerativeLiveTest.class);

    private static final String MODEL = "stealth/space-bunny-alpha";

    private GenerativeReasoningService service() {
        var apiKey = System.getenv("OPENROUTER_API_KEY");
        assertThat(apiKey).isNotBlank();
        var properties = new GenerativeProperties(
                "openai-compatible", "https://openrouter.ai/api/v1", apiKey,
                MODEL, "json-object", 0.0,
                Duration.ofSeconds(10), Duration.ofSeconds(120));
        var httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        var requestFactory = new org.springframework.http.client.JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        var restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader("HTTP-Referer", "http://localhost")
                .build();
        var provider = new OpenAiCompatibleGenerativeProvider(
                (path, payload) -> restClient.post()
                        .uri(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(payload)
                        .retrieve()
                        .body(String.class),
                new com.fasterxml.jackson.databind.ObjectMapper(), properties);
        return new GenerativeReasoningService(
                asset -> new ModelBinding("openai-compatible", MODEL),
                (GenerativeProviderRegistry) id -> Optional.of(provider),
                Clock.systemUTC());
    }

    private ReasoningSpec observationSpec() {
        var fields = new LinkedHashMap<String, String>();
        fields.put("summary", "One-sentence summary of the clause observation.");
        fields.put("issueType", "One of DEVIATION, COMPLIANT, AMBIGUOUS.");
        fields.put("evidenceIds", "Comma-separated evidence ids used, from the supplied set only.");
        var spec = new ReasoningSpec("pre-m7-synthetic-observation", 1,
                "Classify a synthetic clause observation against supplied evidence.",
                1, fields,
                Map.of("issueType", Set.of("DEVIATION", "COMPLIANT", "AMBIGUOUS")),
                Set.of("evidenceIds"));
        return spec;
    }

    private SemanticState state() {
        return new SemanticState() {
            @Override
            public int schemaVersion() {
                return 1;
            }

            @Override
            public Object payload() {
                return Map.of(
                        "clause", "Supplier's total aggregate liability shall not exceed "
                                + "3x fees paid in the preceding 12 months.",
                        "evidence", "EV1: policy cap 1x fees. EV2: approved fallback text.");
            }
        };
    }

    @Test
    void validStructuredResultThroughTheRealChain() {
        GenerativeExecution execution = null;
        Exception lastFailure = null;
        for (int attempt = 0; attempt < 3 && execution == null; attempt++) {
            try {
                long startedAt = System.nanoTime();
                execution = service().generate(observationSpec(), state());
                long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
                log.info("LIVE-GENERATIVE resolvedModel={} usage={} latencyMs={} fields={}",
                        execution.metadata().resolvedModel(), execution.metadata().usage(),
                        elapsedMs, execution.fields());
            } catch (com.rivell.workbench.intelligence.ProviderUnavailableException e) {
                lastFailure = e;
                log.warn("LIVE-GENERATIVE upstream unavailable (attempt {})", attempt + 1);
                try {
                    Thread.sleep(15_000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        // with a free model that does not natively enforce enums, either the model
        // produces a compliant typed result OR fail-closed validation rejects the
        // non-compliant enum — both prove the typed boundary held. A test that
        // never reached a result must be an explicit abort, never a silent pass.
        if (execution == null) {
            org.junit.jupiter.api.Assumptions.abort(
                    "OpenRouter upstream unavailable after 3 attempts: "
                            + (lastFailure == null ? "no execution" : lastFailure.getMessage()));
        }
        assertThat(execution.metadata().resolvedModel()).isNotBlank();
        assertThat(execution.metadata().usage().inputTokens()).isPositive();
        assertThat(execution.fields().keySet()).isEqualTo(observationSpec().fields().keySet());
        assertThat(execution.fields().get("issueType"))
                .isIn("DEVIATION", "COMPLIANT", "AMBIGUOUS");
    }

    @Test
    void providerFailureFailsClosedWithTypedError() {
        var apiKey = System.getenv("OPENROUTER_API_KEY");
        var properties = new GenerativeProperties(
                "openai-compatible", "https://openrouter.ai/api/v1", apiKey,
                "no-such-model-xyz", "strict-schema", 0.0,
                Duration.ofSeconds(10), Duration.ofSeconds(60));
        var provider = new OpenAiCompatibleGenerativeProvider(
                (path, payload) -> RestClient.builder()
                        .baseUrl("https://openrouter.ai/api/v1")
                        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                        .build()
                        .post().uri(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(payload)
                        .retrieve()
                        .body(String.class),
                new ObjectMapper(), properties);
        var service = new GenerativeReasoningService(
                asset -> new ModelBinding("openai-compatible", "no-such-model-xyz"),
                (GenerativeProviderRegistry) id -> Optional.of(provider),
                Clock.systemUTC());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.generate(observationSpec(), state()))
                .isInstanceOf(com.rivell.workbench.intelligence.ProviderRejectedException.class);
    }
}
