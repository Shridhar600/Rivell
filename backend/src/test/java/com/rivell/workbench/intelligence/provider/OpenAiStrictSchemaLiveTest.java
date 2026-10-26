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
import com.rivell.workbench.intelligence.ReasoningSpec;
import com.rivell.workbench.intelligence.SemanticState;

/**
 * Paid OpenAI strict-json-schema conformance smoke: the known-good
 * strict reference route through the REAL chain with a model that natively enforces
 * strict structured output. Opt-in only (OPENAI_LIVE=1 with OPENAI_API_KEY; the key
 * is never printed or logged). ONE small synthetic call — conformance verification,
 * not a quality benchmark.
 */
@EnabledIfEnvironmentVariable(named = "OPENAI_LIVE", matches = "1")
class OpenAiStrictSchemaLiveTest {

    private static final String MODEL = "gpt-6-luna";

    private GenerativeReasoningService service() {
        var apiKey = System.getenv("OPENAI_API_KEY");
        assertThat(apiKey).isNotBlank();
        var properties = new GenerativeProperties(
                "openai-compatible", "https://api.openai.com/v1", apiKey,
                MODEL, GenerativeProperties.RESPONSE_MODE_STRICT_SCHEMA, null,
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

    private ReasoningSpec capSpec() {
        var fields = new LinkedHashMap<String, String>();
        fields.put("capMultiple", "The numeric cap multiple stated by the clause, or 'none'.");
        fields.put("capBasis", "One of the allowed cap bases stated by the clause.");
        return new ReasoningSpec("openai-strict-conformance", 1,
                "Extract liability cap facts from a synthetic clause.",
                1, fields,
                Map.of("capBasis", Set.of("ANNUAL_FEES", "PRECEDING_12_MONTH_FEES")));
    }

    private SemanticState state() {
        return new SemanticState() {
            @Override
            public int schemaVersion() {
                return 1;
            }

            @Override
            public Object payload() {
                return Map.of("clause",
                        "Supplier's total aggregate liability shall not exceed "
                                + "three times the annual fees paid by the Customer.");
            }
        };
    }

    @Test
    void strictJsonSchemaAcceptedWithEnumConstraintAndTypedParse() {
        var execution = service().generate(capSpec(), state());

        var log = org.slf4j.LoggerFactory.getLogger(OpenAiStrictSchemaLiveTest.class);
        log.info("OPENAI-STRICT resolvedModel={} usage={} fields={}",
                execution.metadata().resolvedModel(), execution.metadata().usage(),
                execution.fields());

        assertThat(execution.metadata().resolvedModel()).isNotBlank();
        assertThat(execution.metadata().usage().inputTokens()).isPositive();
        assertThat(execution.fields().keySet()).isEqualTo(capSpec().fields().keySet());
        assertThat(execution.fields().get("capBasis")).isIn("ANNUAL_FEES", "PRECEDING_12_MONTH_FEES");
        assertThat(execution.fields().get("capMultiple")).isNotBlank();
    }

    @Test
    void strictEnumConstraintIsEnforcedUnderAmbiguity() {
        // the clause states a basis that is NOT in the allowed enum; strict
        // structured output must still return one of the allowed values (the
        // schema constrains the shape even when the content is ambiguous)
        var ambiguous = new SemanticState() {
            @Override
            public int schemaVersion() {
                return 1;
            }

            @Override
            public Object payload() {
                return Map.of("clause",
                        "Supplier's total aggregate liability shall not exceed "
                                + "one and a half times the monthly fees paid by the Customer.");
            }
        };
        var execution = service().generate(capSpec(), ambiguous);

        var log = org.slf4j.LoggerFactory.getLogger(OpenAiStrictSchemaLiveTest.class);
        log.info("OPENAI-STRICT-AMBIGUOUS resolvedModel={} usage={} fields={}",
                execution.metadata().resolvedModel(), execution.metadata().usage(),
                execution.fields());

        // fail-closed boundary: the value must be from the closed enum — the
        // schema (not the model's honesty) guarantees this; a junk value like
        // MONTHLY_FEES would have thrown ProviderProtocolException
        assertThat(execution.fields().get("capBasis"))
                .isIn("ANNUAL_FEES", "PRECEDING_12_MONTH_FEES");
        assertThat(execution.fields().keySet()).isEqualTo(capSpec().fields().keySet());
        assertThat(execution.fields().get("capMultiple")).isNotBlank();
    }
}
