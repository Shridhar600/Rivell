package com.rivell.workbench.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rivell.workbench.intelligence.GenerativeProviderRegistry;
import com.rivell.workbench.intelligence.GenerativeReasoningService;
import com.rivell.workbench.intelligence.ModelBinding;
import com.rivell.workbench.intelligence.ProviderUnavailableException;
import com.rivell.workbench.intelligence.provider.GenerativeProperties;
import com.rivell.workbench.intelligence.provider.OpenAiCompatibleGenerativeProvider;

/**
 * Opt-in live generative smoke for {@code m7-open-ended-liability} through the real
 * chain (service → policy → OpenAI-compatible adapter → typed validation). Runs ONLY
 * with OPENROUTER_LIVE=1 and OPENROUTER_API_KEY; the key is never printed or logged.
 * Synthetic fixture only. Engineering observation, NOT a quality claim.
 */
@EnabledIfEnvironmentVariable(named = "OPENROUTER_LIVE", matches = "1")
class M7GenerativeLiveSmokeTest {

    private static final String MODEL = System.getenv("OPENROUTER_MODEL") == null
            ? "stealth/space-bunny-alpha"
            : System.getenv("OPENROUTER_MODEL");

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
        var requestFactory = new org.springframework.http.client.JdkClientHttpRequestFactory(
                httpClient);
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
                new ObjectMapper(), properties);
        return new GenerativeReasoningService(
                asset -> new ModelBinding("openai-compatible", MODEL),
                (GenerativeProviderRegistry) id -> Optional.of(provider),
                Clock.systemUTC());
    }

    @Test
    void openEndedLiabilityProducesTypedCandidateOnSyntheticExoticClause() {
        var spec = M7ReasoningSpecs.openEndedPosition();
        var state = new M7ReasoningSpecs.OpenEndedPositionState(
                1,
                "Supplier's aggregate liability shall not exceed the insurance proceeds "
                        + "actually recovered by Supplier in respect of the claim.",
                "Total liability should not exceed the fees paid in the preceding 12 "
                        + "months. Uncapped or unlimited liability is prohibited.");

        GenerativeReasoningService.GenerativeExecution execution = null;
        Exception lastFailure = null;
        for (int attempt = 0; attempt < 3 && execution == null; attempt++) {
            try {
                long startedAt = System.nanoTime();
                execution = service().generate(spec, state);
                long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
                System.out.println("LIVE-GENERATIVE-M7 resolvedModel="
                        + execution.metadata().resolvedModel()
                        + " usage=" + execution.metadata().usage()
                        + " latencyMs=" + elapsedMs
                        + " proposedLanguage=" + execution.fields()
                                .get(M7ReasoningSpecs.PROPOSED_LANGUAGE)
                        + " explanation=" + execution.fields()
                                .get(M7ReasoningSpecs.EXPLANATION));
            } catch (ProviderUnavailableException e) {
                lastFailure = e;
                System.out.println("LIVE-GENERATIVE-M7 upstream unavailable (attempt "
                        + (attempt + 1) + ")");
                try {
                    Thread.sleep(15_000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        if (execution == null) {
            org.junit.jupiter.api.Assumptions.abort(
                    "OpenRouter upstream unavailable after 3 attempts: "
                            + (lastFailure == null ? "no execution" : lastFailure.getMessage()));
        }
        assertThat(execution.metadata().resolvedModel()).isNotBlank();
        assertThat(execution.metadata().usage().inputTokens()).isPositive();
        assertThat(execution.fields().keySet()).isEqualTo(spec.fields().keySet());
        assertThat(execution.fields().get(M7ReasoningSpecs.PROPOSED_LANGUAGE)).isNotBlank();
        assertThat(execution.fields().get(M7ReasoningSpecs.EXPLANATION)).isNotBlank();
    }
}
