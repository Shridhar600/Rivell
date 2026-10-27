package com.rivell.workbench.intelligence.provider;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.rivell.workbench.intelligence.GenerationRequest;
import com.rivell.workbench.intelligence.GenerationResult;
import com.rivell.workbench.intelligence.GenerativeReasoningProvider;
import com.rivell.workbench.intelligence.NoProviderConfiguredException;
import com.rivell.workbench.intelligence.ProviderProtocolException;
import com.rivell.workbench.intelligence.ProviderRejectedException;
import com.rivell.workbench.intelligence.ProviderTimeoutException;
import com.rivell.workbench.intelligence.ProviderUnavailableException;
import com.rivell.workbench.intelligence.ReasoningSpec;
import com.rivell.workbench.intelligence.TokenUsage;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * OpenAI-compatible generative adapter (M5): serves OpenAI direct, OpenRouter, and
 * the Vercel AI Gateway with base URL + API key + model as configuration. Builds a
 * strict-mode json_schema response format from the reasoning spec, requests
 * temperature 0, and parses the returned JSON object into the typed field map.
 * Fail-closed: truncation, refusal, malformed JSON, or unknown fields never become
 * domain truth. The API key never appears in logs, exceptions, or diagnostics.
 */
public final class OpenAiCompatibleGenerativeProvider implements GenerativeReasoningProvider {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory
            .getLogger(OpenAiCompatibleGenerativeProvider.class);

    static final String PROVIDER_ID = "openai-compatible";
    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";
    // a redrafted clause body can be as long as the clause it replaces (up to 4000 characters
    // are sent); 2000 refused every long clause's wording as "malformed"
    private static final int MAX_FIELD_LENGTH = 8000;

    private final RestClientAccessor restClient;
    private final RestClientAccessor longRestClient;
    private final ObjectMapper objectMapper;
    private final GenerativeProperties properties;
    private final com.rivell.workbench.intelligence.ProviderCreditGuard creditGuard;

    public OpenAiCompatibleGenerativeProvider(
            RestClientAccessor restClient, ObjectMapper objectMapper,
            GenerativeProperties properties) {
        this(restClient, restClient, objectMapper, properties,
                new com.rivell.workbench.intelligence.ProviderCreditGuard(
                        java.time.Clock.systemUTC()));
    }

    public OpenAiCompatibleGenerativeProvider(
            RestClientAccessor restClient, ObjectMapper objectMapper,
            GenerativeProperties properties,
            com.rivell.workbench.intelligence.ProviderCreditGuard creditGuard) {
        this(restClient, restClient, objectMapper, properties, creditGuard);
    }

    public OpenAiCompatibleGenerativeProvider(
            RestClientAccessor restClient, RestClientAccessor longRestClient,
            ObjectMapper objectMapper, GenerativeProperties properties,
            com.rivell.workbench.intelligence.ProviderCreditGuard creditGuard) {
        this.restClient = restClient;
        this.longRestClient = longRestClient == null ? restClient : longRestClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.creditGuard = creditGuard;
    }

    @Override
    public String id() {
        return "openai-compatible";
    }

    @Override
    public GenerationResult generate(GenerationRequest request) {
        if (!properties.isConfigured()) {
            throw new NoProviderConfiguredException(
                    "Generative provider has no API key configured");
        }
        creditGuard.check(creditKey());
        var payload = serialize(request);
        var response = postChatCompletions(payload, request.longRead());
        return toResult(request, response);
    }

    private String creditKey() {
        return "generative|" + id() + "|" + properties.baseUrl();
    }

    private String serialize(GenerationRequest request) {
        try {
            var fields = new LinkedHashMap<String, Object>();
            for (var entry : request.spec().fields().entrySet()) {
                var property = new LinkedHashMap<String, Object>();
                property.put("type", "string");
                property.put("description", entry.getValue());
                var allowed = request.spec().allowedValues().get(entry.getKey());
                if (allowed != null && !allowed.isEmpty()) {
                    property.put("enum", allowed.stream().sorted().toList());
                }
                fields.put(entry.getKey(), property);
            }
            var schema = new OpenAiCompatibleDtos.ChatRequest.ResponseFormatJsonSchema.SchemaWrapper(
                    "object", fields,
                    request.spec().fields().keySet().stream().toList(),
                    false);
            var responseFormat = GenerativeProperties.RESPONSE_MODE_JSON_OBJECT
                    .equals(properties.responseMode())
                    ? OpenAiCompatibleDtos.ChatRequest.ResponseFormat.jsonObject()
                    : OpenAiCompatibleDtos.ChatRequest.ResponseFormat.strictSchema(
                            new OpenAiCompatibleDtos.ChatRequest.ResponseFormatJsonSchema(
                                    schemaName(request.spec().id()), true, schema));
            return objectMapper.writeValueAsString(new OpenAiCompatibleDtos.ChatRequest(
                    request.modelAlias(),
                    List.of(
                            new OpenAiCompatibleDtos.ChatRequest.Message(
                                    "system", systemInstruction(request.spec())),
                            new OpenAiCompatibleDtos.ChatRequest.Message(
                                    "user", userInstruction(request))),
                    properties.temperature(),
                    properties.reasoning().wireEffort(),
                    responseFormat));
        } catch (Exception e) {
            throw new ProviderRejectedException("Generative request could not be serialized", e);
        }
    }

    private String schemaName(String specId) {
        return specId.toLowerCase(java.util.Locale.ROOT)
                .replace('-', '_');
    }

    /**
     * Extracts exactly one markdown-fenced JSON block (transport tolerance for
     * models that fence JSON despite instructions); surrounding prose is
     * discarded and the inner content is still validated strictly. Returns null
     * when there is no complete fence pair.
     */
    private static String stripMarkdownFence(String content) {
        var trimmed = content.strip();
        if (!trimmed.startsWith("```")) {
            return null;
        }
        var firstBreak = trimmed.indexOf('\n');
        if (firstBreak >= 0) {
            var closing = trimmed.lastIndexOf("```");
            if (closing <= firstBreak) {
                return null;
            }
            return trimmed.substring(firstBreak + 1, closing).strip();
        }
        var afterFence = trimmed.substring(3);
        var closing = afterFence.lastIndexOf("```");
        if (closing < 0) {
            return null;
        }
        var inner = afterFence.substring(0, closing);
        if (inner.startsWith("json")) {
            inner = inner.substring(4);
        }
        return inner.strip();
    }

    private String systemInstruction(ReasoningSpec spec) {
        var instruction = new StringBuilder()
                .append(spec.purpose())
                .append(" Return exactly one JSON object with the fields ")
                .append(spec.fields().keySet())
                .append(", following each field description. Output must match the supplied ")
                .append("JSON schema.");
        for (var entry : spec.allowedValues().entrySet()) {
            instruction.append(" Field ").append(entry.getKey())
                    .append(" must be exactly one of ").append(entry.getValue().stream()
                            .sorted().toList()).append('.');
        }
        return instruction.toString();
    }

    private String userInstruction(GenerationRequest request) {
        try {
            return "Input state (JSON):\n"
                    + objectMapper.writeValueAsString(request.state().payload());
        } catch (Exception e) {
            throw new ProviderRejectedException(
                    "Generative request state could not be serialized", e);
        }
    }

    private OpenAiCompatibleDtos.ChatResponse postChatCompletions(
            String payload, boolean longRead) {
        String body;
        try {
            body = (longRead ? longRestClient : restClient).post(CHAT_COMPLETIONS_PATH, payload);
        } catch (RestClientResponseException e) {
            var failure = OpenAiCompatibleErrors.httpFailure(
                    e.getStatusCode().value(), e.getResponseBodyAsString(),
                    e.getResponseHeaders(), "generative");
            recordOutOfCredit(failure);
            throw failure;
        } catch (RestClientException e) {
            throw translateTransportFailure(e);
        }
        if (body == null || body.isBlank()) {
            throw new ProviderProtocolException("Provider returned an empty response body");
        }
        try {
            return objectMapper.readValue(body, OpenAiCompatibleDtos.ChatResponse.class);
        } catch (Exception e) {
            throw new ProviderProtocolException("Provider response could not be parsed");
        }
    }

    private void recordOutOfCredit(RuntimeException failure) {
        if (failure instanceof com.rivell.workbench.intelligence.ProviderOutOfCreditException) {
            creditGuard.record(creditKey());
        }
    }

    private ProviderUnavailableException translateTransportFailure(RestClientException e) {
        var cause = e.getCause();
        while (cause != null) {
            if (cause instanceof HttpTimeoutException) {
                throw new ProviderTimeoutException("Provider did not respond in time");
            }
            if (cause instanceof HttpConnectTimeoutException) {
                throw new ProviderTimeoutException("Provider connection timed out");
            }
            cause = cause.getCause();
        }
        return new ProviderUnavailableException("Provider is unreachable", e);
    }

    private GenerationResult toResult(
            GenerationRequest request, OpenAiCompatibleDtos.ChatResponse response) {
        if (response.error() != null) {
            var failure = OpenAiCompatibleErrors.errorResponse(
                    response.error().code(), response.error().type(), "generative");
            recordOutOfCredit(failure);
            throw failure;
        }
        if (response.model() == null || response.model().isBlank()) {
            throw new ProviderProtocolException(
                    "Provider response is missing the model identifier");
        }
        if (response.usage() == null
                || response.usage().promptTokens() == null
                || response.usage().completionTokens() == null) {
            throw new ProviderProtocolException("Provider response has no token usage");
        }
        if (response.choices() == null || response.choices().size() != 1) {
            throw new ProviderProtocolException("Provider response must have exactly one choice");
        }
        var choice = response.choices().get(0);
        if (choice.message() == null || choice.message().refusal() != null
                && !choice.message().refusal().isBlank()) {
            throw new ProviderRejectedException("Provider refused to answer");
        }
        if (!"stop".equals(choice.finishReason())) {
            throw new ProviderProtocolException(
                    "Provider response is incomplete (finish reason " + choice.finishReason() + ")");
        }
        var fields = parseFields(request, choice.message().content());
        return new GenerationResult(
                fields, response.model(),
                new TokenUsage(response.usage().promptTokens(), response.usage().completionTokens()));
    }

    private Map<String, String> parseFields(GenerationRequest request, String content) {
        JsonNode node;
        try {
            node = objectMapper.readTree(content);
        } catch (Exception e) {
            // small models frequently wrap JSON in a markdown fence; stripping one
            // fence is transport tolerance — the content must still be exactly one
            // JSON object and is otherwise validated strictly
            var stripped = stripMarkdownFence(content);
            if (stripped == null) {
                throw new ProviderProtocolException("Generative content is not valid JSON");
            }
            log.warn("Generative content required markdown-fence transport tolerance");
            try {
                node = objectMapper.readTree(stripped);
            } catch (Exception e2) {
                throw new ProviderProtocolException("Generative content is not valid JSON");
            }
        }
        if (!node.isObject()) {
            throw new ProviderProtocolException("Generative content is not a JSON object");
        }
        var fields = new LinkedHashMap<String, String>();
        for (var name : request.spec().fields().keySet()) {
            var value = node.get(name);
            var textual = asTextual(value, request.spec().listFields().contains(name));
            if (textual == null || textual.isBlank()
                    || textual.length() > MAX_FIELD_LENGTH) {
                throw new ProviderProtocolException(
                        "Generative content is missing or malformed field " + name);
            }
            fields.put(name, textual);
        }
        for (var entry : node.properties()) {
            if (!request.spec().fields().containsKey(entry.getKey())) {
                throw new ProviderProtocolException(
                        "Generative content contains unknown field " + entry.getKey());
            }
        }
        return fields;
    }

    /**
     * Field values are domain strings. Only fields the spec explicitly declares
     * list-shaped may arrive as a JSON array of strings; joining is transport
     * normalization for those declared fields alone — anything else fails closed
     * rather than inventing a value the spec never declared.
     */
    private static String asTextual(JsonNode value, boolean listShaped) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isTextual()) {
            return value.asText();
        }
        if (value.isArray() && listShaped) {
            var joined = new StringBuilder();
            for (var element : value) {
                if (!element.isTextual()) {
                    return null;
                }
                if (joined.length() > 0) {
                    joined.append(',');
                }
                joined.append(element.asText());
            }
            return joined.toString();
        }
        return null;
    }

    public interface RestClientAccessor {

        String post(String path, String payload);
    }
}
