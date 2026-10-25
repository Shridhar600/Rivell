package com.rivell.workbench.intelligence.provider;

import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rivell.workbench.intelligence.EmbeddingBatch;
import com.rivell.workbench.intelligence.EmbeddingProvider;
import com.rivell.workbench.intelligence.EmbeddingRequest;
import com.rivell.workbench.intelligence.NoProviderConfiguredException;
import com.rivell.workbench.intelligence.ProviderCreditGuard;
import com.rivell.workbench.intelligence.ProviderOutOfCreditException;
import com.rivell.workbench.intelligence.ProviderProtocolException;
import com.rivell.workbench.intelligence.ProviderRateLimitedException;
import com.rivell.workbench.intelligence.ProviderRejectedException;
import com.rivell.workbench.intelligence.ProviderTimeoutException;
import com.rivell.workbench.intelligence.ProviderUnavailableException;
import com.rivell.workbench.intelligence.TokenUsage;

/**
 * OpenAI-compatible embedding adapter (M6): serves OpenAI direct and OpenRouter
 * with base URL + API key + model as configuration. Sends batched inputs, validates
 * response ordering (data[i].index == i) and dimensionality, and reports usage.
 * Fail-closed: malformed or partial responses never become domain vectors. A batch that
 * the provider rejects as too large is split recursively, so one oversized input cannot
 * discard the good inputs beside it. The API key never appears in logs, exceptions, or
 * diagnostics.
 */
public final class OpenAiCompatibleEmbeddingProvider implements EmbeddingProvider {

    static final String PROVIDER_ID = "openai-compatible";
    private static final String EMBEDDINGS_PATH = "/embeddings";
    /** OpenAI accepts at most 300 000 tokens per request; keep a conservative margin. */
    static final int MAX_TOTAL_ESTIMATED_TOKENS = 250_000;
    static final int MAX_INPUTS_PER_REQUEST = 2048;

    private final RestClientAccessor restClient;
    private final ObjectMapper objectMapper;
    private final EmbeddingProperties properties;
    private final ProviderCreditGuard creditGuard;

    public OpenAiCompatibleEmbeddingProvider(
            RestClientAccessor restClient, ObjectMapper objectMapper,
            EmbeddingProperties properties) {
        this(restClient, objectMapper, properties,
                new ProviderCreditGuard(java.time.Clock.systemUTC()));
    }

    public OpenAiCompatibleEmbeddingProvider(
            RestClientAccessor restClient, ObjectMapper objectMapper,
            EmbeddingProperties properties, ProviderCreditGuard creditGuard) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.creditGuard = creditGuard;
    }

    @Override
    public String id() {
        return "openai-compatible";
    }

    private String creditKey() {
        return "embedding|" + id() + "|" + properties.baseUrl();
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
        creditGuard.check(creditKey());
        var vectors = new ArrayList<float[]>(request.inputs().size());
        long totalPromptTokens = 0L;
        String resolvedModel = null;
        for (var batch : partition(request.inputs(), properties.maxInputsPerBatch())) {
            var response = fetch(request, batch);
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

    /**
     * One batch, split in half when the provider says the payload is too large. Only a single
     * input that still fails propagates, and only that input degrades.
     */
    private OpenAiCompatibleDtos.EmbeddingResponse fetch(
            EmbeddingRequest request, List<String> batch) {
        try {
            return postEmbeddings(request, batch);
        } catch (ProviderRejectedException e) {
            var status = e.httpStatus();
            if (batch.size() > 1 && status != null && (status == 400 || status == 413)) {
                var middle = batch.size() / 2;
                var left = submitHalf(request, batch.subList(0, middle));
                var right = submitHalf(request, batch.subList(middle, batch.size()));
                if (left.failure() != null) {
                    throw left.failure();
                }
                if (right.failure() != null) {
                    throw right.failure();
                }
                if (!java.util.Objects.equals(
                        left.response().model(), right.response().model())) {
                    throw new ProviderProtocolException(
                            "Embedding split batches resolved to inconsistent models");
                }
                var data = new ArrayList<OpenAiCompatibleDtos.EmbeddingResponse.EmbeddingItem>(
                        left.response().data());
                var offset = middle;
                for (var item : right.response().data()) {
                    data.add(new OpenAiCompatibleDtos.EmbeddingResponse.EmbeddingItem(
                            item.index() + offset, item.embedding()));
                }
                var usage = new OpenAiCompatibleDtos.Usage(
                        tokens(left.response().usage()) + tokens(right.response().usage()), 0);
                return new OpenAiCompatibleDtos.EmbeddingResponse(
                        left.response().model(), data, usage);
            }
            throw e;
        }
    }

    /** Each half is attempted even when the other fails, so one bad input cannot hide the rest. */
    private Half submitHalf(EmbeddingRequest request, List<String> batch) {
        try {
            var response = fetch(request, batch);
            if (response.data() == null || response.data().size() != batch.size()) {
                throw new ProviderProtocolException(
                        "Embedding response does not match the batch size");
            }
            return new Half(response, null);
        } catch (ProviderRejectedException e) {
            var status = e.httpStatus();
            if (status != null && (status == 400 || status == 413)) {
                return new Half(null, e);
            }
            throw e;
        }
    }

    private record Half(OpenAiCompatibleDtos.EmbeddingResponse response,
            RuntimeException failure) {
    }

    private static int tokens(OpenAiCompatibleDtos.Usage usage) {
        return usage == null || usage.promptTokens() == null ? 0 : usage.promptTokens();
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
            var failure = OpenAiCompatibleErrors.httpFailure(
                    e.getStatusCode().value(), e.getResponseBodyAsString(),
                    e.getResponseHeaders(), "embedding");
            if (failure instanceof ProviderOutOfCreditException) {
                creditGuard.record(creditKey());
            }
            throw failure;
        } catch (RestClientException e) {
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

    private ProviderUnavailableException translateTransportFailure(RestClientException e) {
        var cause = e.getCause();
        while (cause != null) {
            if (cause instanceof HttpTimeoutException) {
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

    /**
     * Partitions by the configured input count and by an estimated total token budget (one
     * token per three characters), so a request stays inside the provider's per-request cap.
     */
    static List<List<String>> partition(List<String> inputs, int maxSize) {
        var limit = Math.min(Math.max(1, maxSize), MAX_INPUTS_PER_REQUEST);
        var batches = new ArrayList<List<String>>();
        var current = new ArrayList<String>();
        long estimatedTokens = 0L;
        for (var input : inputs) {
            var tokens = estimatedTokens(input);
            if (!current.isEmpty()
                    && (current.size() >= limit
                            || estimatedTokens + tokens > MAX_TOTAL_ESTIMATED_TOKENS)) {
                batches.add(List.copyOf(current));
                current = new ArrayList<>();
                estimatedTokens = 0L;
            }
            current.add(input);
            estimatedTokens += tokens;
        }
        if (!current.isEmpty()) {
            batches.add(List.copyOf(current));
        }
        return batches;
    }

    private static long estimatedTokens(String input) {
        var length = input == null ? 0 : input.length();
        return (length + 2L) / 3L;
    }

    public interface RestClientAccessor {
        String post(String path, String payload);
    }
}
