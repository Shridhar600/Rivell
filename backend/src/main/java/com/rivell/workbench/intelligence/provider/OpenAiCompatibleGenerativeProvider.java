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

    static final String PROVIDER_ID = "openai-compatible";
    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";
    private static final int MAX_FIELD_LENGTH = 500;

    private final RestClientAccessor restClient;
    private final ObjectMapper objectMapper;
    private final GenerativeProperties properties;

    public OpenAiCompatibleGenerativeProvider(
            RestClientAccessor restClient, ObjectMapper objectMapper,
            GenerativeProperties properties) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
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
        var payload = serialize(request);
        var response = postChatCompletions(payload);
        return toResult(request, response);
    }

    private String serialize(GenerationRequest request) {
        try {
            var fields = new LinkedHashMap<String, Object>();
            for (var entry : request.spec().fields().entrySet()) {
                var property = new LinkedHashMap<String, Object>();
                property.put("type", "string");
                property.put("description", entry.getValue());
                fields.put(entry.getKey(), property);
            }
            var schema = new OpenAiCompatibleDtos.ChatRequest.ResponseFormatJsonSchema.SchemaWrapper(
                    "object", fields,
                    request.spec().fields().keySet().stream().toList(),
                    false);
            return objectMapper.writeValueAsString(new OpenAiCompatibleDtos.ChatRequest(
                    request.modelAlias(),
                    List.of(
                            new OpenAiCompatibleDtos.ChatRequest.Message(
                                    "system", systemInstruction(request.spec())),
                            new OpenAiCompatibleDtos.ChatRequest.Message(
                                    "user", userInstruction(request))),
                    0.0,
                    new OpenAiCompatibleDtos.ChatRequest.ResponseFormat(
                            new OpenAiCompatibleDtos.ChatRequest.ResponseFormatJsonSchema(
                                    schemaName(request.spec().id()), true, schema))));
        } catch (Exception e) {
            throw new ProviderRejectedException("Generative request could not be serialized", e);
        }
    }

    private String schemaName(String specId) {
        return specId.toLowerCase(java.util.Locale.ROOT)
                .replace('-', '_');
    }

    private String systemInstruction(ReasoningSpec spec) {
        return "You extract structured data from legal documents. You are given a document "
                + "excerpt and must return exactly one JSON object with the fields "
                + spec.fields().keySet() + ". Extract only what the excerpt states. "
                + "Never invent values. Output must match the supplied JSON schema.";
    }

    private String userInstruction(GenerationRequest request) {
        return "Document excerpt:\n" + request.state().payload();
    }

    private OpenAiCompatibleDtos.ChatResponse postChatCompletions(String payload) {
        String body;
        try {
            body = restClient.post(CHAT_COMPLETIONS_PATH, payload);
        } catch (RestClientResponseException e) {
            throw mapHttpFailure(e.getStatusCode().value());
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

    private RuntimeException mapHttpFailure(int status) {
        if (status == 429) {
            return new ProviderUnavailableException("Provider rate limited (HTTP 429)");
        }
        if (status >= 400 && status < 500) {
            return new ProviderRejectedException(
                    "Provider rejected the generative request (HTTP " + status + ")");
        }
        if (status >= 500) {
            return new ProviderUnavailableException("Provider unavailable (HTTP " + status + ")");
        }
        return new ProviderProtocolException("Provider returned unexpected HTTP status " + status);
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
        if (response.model() == null || response.model().isBlank()) {
            throw new ProviderProtocolException("Provider response has no resolved model");
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
            throw new ProviderProtocolException("Generative content is not valid JSON");
        }
        if (!node.isObject()) {
            throw new ProviderProtocolException("Generative content is not a JSON object");
        }
        var fields = new LinkedHashMap<String, String>();
        for (var name : request.spec().fields().keySet()) {
            var value = node.get(name);
            if (value == null || value.isNull() || !value.isTextual()
                    || value.asText().isBlank() || value.asText().length() > MAX_FIELD_LENGTH) {
                throw new ProviderProtocolException(
                        "Generative content is missing or malformed field " + name);
            }
            fields.put(name, value.asText());
        }
        for (var entry : node.properties()) {
            if (!request.spec().fields().containsKey(entry.getKey())) {
                throw new ProviderProtocolException(
                        "Generative content contains unknown field " + entry.getKey());
            }
        }
        return fields;
    }

    public interface RestClientAccessor {

        String post(String path, String payload);
    }
}
