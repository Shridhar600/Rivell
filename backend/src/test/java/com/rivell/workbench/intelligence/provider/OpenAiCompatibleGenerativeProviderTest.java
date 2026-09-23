package com.rivell.workbench.intelligence.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

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
