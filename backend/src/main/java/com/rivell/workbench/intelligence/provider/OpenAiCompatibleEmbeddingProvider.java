package com.rivell.workbench.intelligence.provider;

import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rivell.workbench.intelligence.EmbeddingBatch;
import com.rivell.workbench.intelligence.EmbeddingProvider;
import com.rivell.workbench.intelligence.EmbeddingRequest;
import com.rivell.workbench.intelligence.NoProviderConfiguredException;
import com.rivell.workbench.intelligence.ProviderProtocolException;
import com.rivell.workbench.intelligence.ProviderRejectedException;
import com.rivell.workbench.intelligence.ProviderTimeoutException;
import com.rivell.workbench.intelligence.ProviderUnavailableException;
import com.rivell.workbench.intelligence.TokenUsage;

/**
 * OpenAI-compatible embedding adapter (M6): serves OpenAI direct and OpenRouter
 * with base URL + API key + model as configuration. Sends batched inputs, validates
 * response ordering (data[i].index == i) and dimensionality, and reports usage.
 * Fail-closed: malformed or partial responses never become domain vectors. The API
 * key never appears in logs, exceptions, or diagnostics.
 */
public final class OpenAiCompatibleEmbeddingProvider implements EmbeddingProvider {

    static final String PROVIDER_ID = "openai-compatible";
    private static final String EMBEDDINGS_PATH = "/embeddings";

    private final RestClientAccessor restClient;
    private final ObjectMapper objectMapper;
    private final EmbeddingProperties properties;

    public OpenAiCompatibleEmbeddingProvider(
            RestClientAccessor restClient, ObjectMapper objectMapper,
            EmbeddingProperties properties) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public String id() {
        return "openai-compatible";
    }

    @Override
    public EmbeddingBatch embed(EmbeddingRequest request) {
        if (!properties.isConfigured()) {
            throw new NoProviderConfiguredException(
                    "Embedding provider has no API key configured");
        }
        if (request.inputs().isEmpty()) {
            return new EmbeddingBatch(List.of(), properties.model(), null);
        }
        var vectors = new ArrayList<float[]>(request.inputs().size());
        long totalPromptTokens = 0L;
        String resolvedModel = null;
        var batches = partition(request.inputs(), properties.maxInputsPerBatch());
        for (var batch : batches) {
            var response = postEmbeddings(request, batch);
            if (response.data() == null || response.data().size() != batch.size()) {
                throw new ProviderProtocolException(
                        "Embedding response does not match the batch size");
            }
            for (var item : response.data()) {
                if (item.index() == null || item.index() < 0
                        || item.index() >= batch.size()
                        || item.embedding() == null || item.embedding().isEmpty()) {
                    throw new ProviderProtocolException(
                            "Embedding response has malformed items");
                }
            }
            var ordered = new float[batch.size()][];
            for (var item : response.data()) {
                var vector = new float[item.embedding().size()];
                for (int i = 0; i < vector.length; i++) {
                    vector[i] = item.embedding().get(i).floatValue();
                }
                ordered[item.index()] = vector;
            }
            for (var vector : ordered) {
                if (vector == null) {
                    throw new ProviderProtocolException("Embedding response has gaps");
                }
                vectors.add(vector);
            }
            if (response.model() == null || response.model().isBlank()) {
                throw new ProviderProtocolException("Embedding response has no resolved model");
            }
            if (resolvedModel != null && !resolvedModel.equals(response.model())) {
                throw new ProviderProtocolException(
                        "Embedding batches resolved to inconsistent models");
            }
            resolvedModel = response.model();
            if (response.usage() != null && response.usage().promptTokens() != null) {
                totalPromptTokens += response.usage().promptTokens();
            }
        }
        if (request.dimensions() != null
                && vectors.stream().anyMatch(vector -> vector.length != request.dimensions())) {
            throw new ProviderProtocolException(
                    "Embedding vectors do not match the requested dimensions");
        }
        return new EmbeddingBatch(
                List.copyOf(vectors), resolvedModel, new TokenUsage((int) totalPromptTokens, 0));
    }

    private OpenAiCompatibleDtos.EmbeddingResponse postEmbeddings(
            EmbeddingRequest request, List<String> batch) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("model", request.model());
        payload.put("input", batch);
        payload.put("encoding_format", "float");
        if (request.dimensions() != null) {
            payload.put("dimensions", request.dimensions());
        }
        String body;
        try {
            body = restClient.post(EMBEDDINGS_PATH, serialize(payload));
        } catch (RestClientResponseException e) {
            throw mapHttpFailure(e.getStatusCode().value(), e.getResponseBodyAsString());
        } catch (org.springframework.web.client.RestClientException e) {
            throw translateTransportFailure(e);
        }
        if (body == null || body.isBlank()) {
            throw new ProviderProtocolException("Embedding response body is empty");
        }
        try {
            return objectMapper.readValue(body, OpenAiCompatibleDtos.EmbeddingResponse.class);
        } catch (Exception e) {
            throw new ProviderProtocolException("Embedding response could not be parsed");
        }
    }

    private RuntimeException mapHttpFailure(int status, String body) {
        // OpenAI answers an exhausted account with 429 too; waiting does not help, so fail fast
        // (only the error code is read, never logged)
        if (status == 429 && body != null && body.contains("insufficient_quota")) {
            return new ProviderRejectedException("Provider account has no credits (HTTP 429 insufficient_quota)");
        }
        if (status == 429) {
            return new ProviderUnavailableException("Embedding provider rate limited (HTTP 429)");
        }
        if (status >= 400 && status < 500) {
            return new ProviderRejectedException(
                    "Embedding provider rejected the request (HTTP " + status + ")");
        }
        if (status >= 500) {
            return new ProviderUnavailableException(
                    "Embedding provider unavailable (HTTP " + status + ")");
        }
        return new ProviderProtocolException(
                "Embedding provider returned unexpected HTTP status " + status);
    }

    private ProviderUnavailableException translateTransportFailure(
            org.springframework.web.client.RestClientException e) {
        var cause = e.getCause();
        while (cause != null) {
            if (cause instanceof HttpTimeoutException
                    || cause instanceof HttpConnectTimeoutException) {
                throw new ProviderTimeoutException("Embedding provider did not respond in time");
            }
            cause = cause.getCause();
        }
        return new ProviderUnavailableException("Embedding provider is unreachable", e);
    }

    private String serialize(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new ProviderProtocolException("Embedding request could not be serialized");
        }
    }

    private static List<List<String>> partition(List<String> inputs, int maxSize) {
        var batches = new ArrayList<List<String>>();
        for (int i = 0; i < inputs.size(); i += maxSize) {
            batches.add(inputs.subList(i, Math.min(i + maxSize, inputs.size())));
        }
        return batches;
    }

    public interface RestClientAccessor {
        String post(String path, String payload);
    }
}
