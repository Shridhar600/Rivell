package com.rivell.workbench.intelligence.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.rivell.workbench.intelligence.ChoiceQuestion;
import com.rivell.workbench.intelligence.DecisionExecutionRequest;
import com.rivell.workbench.intelligence.NoProviderConfiguredException;
import com.rivell.workbench.intelligence.ProviderProtocolException;
import com.rivell.workbench.intelligence.ProviderRejectedException;
import com.rivell.workbench.intelligence.ProviderTimeoutException;
import com.rivell.workbench.intelligence.ProviderUnavailableException;
import com.rivell.workbench.intelligence.ProviderExecutionResult;
import com.rivell.workbench.intelligence.RatingQuestion;
import com.rivell.workbench.intelligence.SemanticAnswer;
import com.rivell.workbench.intelligence.SemanticDecisionProvider;
import com.rivell.workbench.intelligence.SemanticQuestion;
import com.rivell.workbench.intelligence.TokenUsage;
import com.rivell.workbench.intelligence.YesNoQuestion;

/**
 * TypeSafe System One (Jev) adapter. All provider-specific transport, serialization and
 * error behavior lives here; responses are validated strictly and mapped to neutral answers.
 * The API key never appears in logs, exceptions, or request diagnostics.
 */
public final class JevProvider implements SemanticDecisionProvider {

    static final String PROVIDER_ID = "jev";
    static final String MODELS_PATH = "/v1/models";
    static final String DECISIONS_PATH = "/v1/systemone";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final JevProperties properties;

    public JevProvider(JevProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        var httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                .build();
    }

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    List<JevRequestDtos.ModelMetadata> listModels() {
        var response = restClient.get()
                .uri(MODELS_PATH)
                .retrieve()
                .body(JevRequestDtos.ModelMetadataList.class);
        if (response == null || response.models() == null) {
            throw new ProviderProtocolException("Model discovery returned no models");
        }
        return response.models();
    }

    @Override
    public ProviderExecutionResult execute(DecisionExecutionRequest request) {
        if (!properties.isConfigured()) {
            throw new NoProviderConfiguredException(
                    "Jev provider has no API key configured");
        }
        var payload = serialize(request);
        var response = postDecisions(payload);
        return toResult(request, response);
    }

    private JevRequestDtos.SystemOneResponse postDecisions(String payload) {
        String body;
        try {
            body = restClient.post()
                    .uri(DECISIONS_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            throw mapHttpFailure(e.getStatusCode());
        } catch (RestClientException e) {
            throw translateTransportFailure(e);
        }
        if (body == null || body.isBlank()) {
            throw new ProviderProtocolException("Provider returned an empty response body");
        }
        try {
            return objectMapper.readValue(body, JevRequestDtos.SystemOneResponse.class);
        } catch (JsonProcessingException e) {
            throw new ProviderProtocolException("Provider response could not be parsed");
        }
    }

    private RuntimeException mapHttpFailure(HttpStatusCode status) {
        if (status.value() == 422) {
            return new ProviderRejectedException("Provider rejected the decision request (validation failure)");
        }
        if (status.value() == 429) {
            return new ProviderUnavailableException("Provider rate limited (HTTP 429)");
        }
        if (status.is4xxClientError()) {
            return new ProviderRejectedException("Provider rejected the decision request (HTTP " + status.value() + ")");
        }
        if (status.is5xxServerError()) {
            return new ProviderUnavailableException("Provider unavailable (HTTP " + status.value() + ")");
        }
        return new ProviderProtocolException("Provider returned unexpected HTTP status " + status.value());
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

    private ProviderExecutionResult toResult(
            DecisionExecutionRequest request,
            JevRequestDtos.SystemOneResponse response) {
        if (response == null || response.model() == null || response.model().isBlank()) {
            throw new ProviderProtocolException("Provider response has no resolved model");
        }
        if (response.usage() == null
                || response.usage().inputTokens() == null || response.usage().outputTokens() == null) {
            throw new ProviderProtocolException("Provider response has no token usage");
        }
        var answers = new LinkedHashMap<String, SemanticAnswer>();
        for (var entry : request.pack().questions().entrySet()) {
            var node = response.answers() == null ? null : response.answers().get(entry.getKey());
            if (node == null || node.isNull()) {
                throw new ProviderProtocolException("Provider response is missing answer " + entry.getKey());
            }
            answers.put(entry.getKey(), JevAnswerMapper.map(entry.getKey(), entry.getValue(), node));
        }
        return new ProviderExecutionResult(
                answers,
                response.model(),
                new TokenUsage(response.usage().inputTokens(), response.usage().outputTokens()));
    }

    private String serialize(DecisionExecutionRequest request) {
        var questions = new LinkedHashMap<String, Object>();
        for (var entry : request.pack().questions().entrySet()) {
            questions.put(entry.getKey(), questionPayload(entry.getKey(), entry.getValue()));
        }
        try {
            return objectMapper.writeValueAsString(
                    new JevRequestDtos.SystemOneRequest(request.modelAlias(), request.state().payload(), questions));
        } catch (JsonProcessingException e) {
            throw new ProviderRejectedException("Decision state could not be serialized", e);
        }
    }

    private Object questionPayload(String name, SemanticQuestion question) {
        if (question instanceof YesNoQuestion yesNo) {
            return new JevRequestDtos.NoulQuestion(yesNo.instructions(), yesNo.trueCriterion(), yesNo.falseCriterion());
        }
        if (question instanceof ChoiceQuestion choice) {
            return new JevRequestDtos.ChoiceQuestion(choice.instructions(),
                    new LinkedHashMap<>(choice.criteria()));
        }
        if (question instanceof RatingQuestion rating) {
            return new JevRequestDtos.ScoreQuestion(rating.instructions(), rating.levels());
        }
        throw new ProviderRejectedException("Unsupported question primitive for " + name);
    }
}
