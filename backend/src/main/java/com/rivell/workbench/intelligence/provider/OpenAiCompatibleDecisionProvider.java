package com.rivell.workbench.intelligence.provider;

import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rivell.workbench.intelligence.ChoiceAnswer;
import com.rivell.workbench.intelligence.ChoiceQuestion;
import com.rivell.workbench.intelligence.DecisionExecutionRequest;
import com.rivell.workbench.intelligence.NoProviderConfiguredException;
import com.rivell.workbench.intelligence.ProviderExecutionResult;
import com.rivell.workbench.intelligence.ProviderProtocolException;
import com.rivell.workbench.intelligence.ProviderRejectedException;
import com.rivell.workbench.intelligence.ProviderTimeoutException;
import com.rivell.workbench.intelligence.ProviderUnavailableException;
import com.rivell.workbench.intelligence.RatingQuestion;
import com.rivell.workbench.intelligence.SemanticAnswer;
import com.rivell.workbench.intelligence.SemanticDecisionProvider;
import com.rivell.workbench.intelligence.TokenUsage;
import com.rivell.workbench.intelligence.YesNoAnswer;
import com.rivell.workbench.intelligence.YesNoQuestion;

/**
 * A general LLM as the decision provider (step-6 comparison "Rivell with an LLM as decider"):
 * the same bounded question packs Jev answers, asked of an OpenAI-compatible chat model with a
 * strict JSON schema. The model states an answer and its confidence; the confidence becomes the
 * probability the domain code thresholds (an LLM's stated confidence is not calibrated like
 * Jev's probabilities, which is part of what the comparison measures). Selected with
 * {@code rivell.intelligence.provider=openai-decider}; uses the generative model and key.
 */
public final class OpenAiCompatibleDecisionProvider implements SemanticDecisionProvider {

    static final String PROVIDER_ID = "openai-decider";
    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";
    private static final String SYSTEM = "You answer bounded questions about one input state for a "
            + "contract review tool. Answer every question independently from the state alone. "
            + "A yes/no question is true when its 'trueWhen' criterion holds and false when its "
            + "'falseWhen' criterion holds. A choice question takes exactly one option key. For each "
            + "answer give your confidence from 0 to 1 that the answer is right.";

    private final OpenAiCompatibleGenerativeProvider.RestClientAccessor restClient;
    private final ObjectMapper objectMapper;
    private final GenerativeProperties properties;
    private final com.rivell.workbench.intelligence.ProviderCreditGuard creditGuard;

    public OpenAiCompatibleDecisionProvider(
            OpenAiCompatibleGenerativeProvider.RestClientAccessor restClient, ObjectMapper objectMapper,
            GenerativeProperties properties) {
        this(restClient, objectMapper, properties,
                new com.rivell.workbench.intelligence.ProviderCreditGuard(
                        java.time.Clock.systemUTC()));
    }

    public OpenAiCompatibleDecisionProvider(
            OpenAiCompatibleGenerativeProvider.RestClientAccessor restClient, ObjectMapper objectMapper,
            GenerativeProperties properties,
            com.rivell.workbench.intelligence.ProviderCreditGuard creditGuard) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.creditGuard = creditGuard;
    }

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public ProviderExecutionResult execute(DecisionExecutionRequest request) {
        if (!properties.isConfigured()) {
            throw new NoProviderConfiguredException("LLM decision provider has no API key configured");
        }
        creditGuard.check(creditKey());
        String body;
        try {
            body = restClient.post(CHAT_COMPLETIONS_PATH, serialize(request));
        } catch (RestClientResponseException e) {
            var failure = OpenAiCompatibleErrors.httpFailure(
                    e.getStatusCode().value(), e.getResponseBodyAsString(),
                    e.getResponseHeaders(), "decision");
            if (failure instanceof com.rivell.workbench.intelligence.ProviderOutOfCreditException) {
                creditGuard.record(creditKey());
            }
            throw failure;
        } catch (RestClientException e) {
            throw translateTransportFailure(e);
        }
        if (body == null || body.isBlank()) {
            throw new ProviderProtocolException("Provider returned an empty response body");
        }
        OpenAiCompatibleDtos.ChatResponse response;
        try {
            response = objectMapper.readValue(body, OpenAiCompatibleDtos.ChatResponse.class);
        } catch (Exception e) {
            throw new ProviderProtocolException("Provider response could not be parsed");
        }
        if (response.choices() == null || response.choices().size() != 1
                || response.choices().get(0).message() == null) {
            throw new ProviderProtocolException("Provider response must have exactly one choice");
        }
        var choice = response.choices().get(0);
        if (!"stop".equals(choice.finishReason())) {
            throw new ProviderProtocolException(
                    "Provider response is incomplete (finish reason " + choice.finishReason() + ")");
        }
        var usage = response.usage() == null || response.usage().promptTokens() == null
                ? new TokenUsage(0, 0)
                : new TokenUsage(response.usage().promptTokens(), response.usage().completionTokens());
        return new ProviderExecutionResult(answers(request, choice.message().content()),
                response.model(), usage);
    }

    private String serialize(DecisionExecutionRequest request) {
        var questions = objectMapper.createObjectNode();
        var properties = objectMapper.createObjectNode();
        var required = objectMapper.createArrayNode();
        request.pack().questions().forEach((name, question) -> {
            var described = questions.putObject(name);
            var answer = properties.putObject(name);
            answer.put("type", "object");
            answer.put("additionalProperties", false);
            var fields = answer.putObject("properties");
            switch (question) {
                case YesNoQuestion yesNo -> {
                    described.put("type", "yes/no");
                    described.put("question", yesNo.instructions());
                    if (yesNo.trueCriterion() != null) {
                        described.put("trueWhen", yesNo.trueCriterion());
                    }
                    if (yesNo.falseCriterion() != null) {
                        described.put("falseWhen", yesNo.falseCriterion());
                    }
                    fields.putObject("answer").put("type", "string").putArray("enum").add("yes").add("no");
                }
                case ChoiceQuestion choice -> {
                    described.put("type", "choice");
                    described.put("question", choice.instructions());
                    var options = described.putObject("options");
                    var keys = fields.putObject("answer").put("type", "string").putArray("enum");
                    choice.criteria().forEach((key, criterion) -> {
                        options.put(key, criterion);
                        keys.add(key);
                    });
                }
                case RatingQuestion rating -> throw new ProviderRejectedException(
                        "Unsupported question primitive for " + name);
            }
            fields.putObject("confidence").put("type", "number");
            answer.putArray("required").add("answer").add("confidence");
            required.add(name);
        });
        var schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.set("properties", properties);
        schema.set("required", required);

        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", request.modelAlias());
        var reasoningEffort = this.properties.reasoning().wireEffort();
        if (reasoningEffort != null) {
            root.put("reasoning_effort", reasoningEffort);
        }
        var messages = root.putArray("messages");
        messages.addObject().put("role", "system").put("content", SYSTEM);
        try {
            var input = objectMapper.createObjectNode();
            input.set("state", objectMapper.valueToTree(request.state().payload()));
            input.set("questions", questions);
            messages.addObject().put("role", "user").put("content", objectMapper.writeValueAsString(input));
        } catch (Exception e) {
            throw new ProviderRejectedException("Decision state could not be serialized", e);
        }
        var format = root.putObject("response_format");
        format.put("type", "json_schema");
        var jsonSchema = format.putObject("json_schema");
        jsonSchema.put("name", "decisions");
        jsonSchema.put("strict", true);
        jsonSchema.set("schema", schema);
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new ProviderRejectedException("Decision request could not be serialized", e);
        }
    }

    private Map<String, SemanticAnswer> answers(DecisionExecutionRequest request, String content) {
        JsonNode node;
        try {
            node = objectMapper.readTree(content);
        } catch (Exception e) {
            throw new ProviderProtocolException("Provider answer is not JSON");
        }
        var answers = new LinkedHashMap<String, SemanticAnswer>();
        request.pack().questions().forEach((name, question) -> {
            var answer = node.path(name);
            var value = answer.path("answer").asText(null);
            if (value == null || !answer.path("confidence").isNumber()) {
                throw new ProviderProtocolException("Provider response is missing answer " + name);
            }
            var confidence = Math.max(0.0, Math.min(1.0, answer.path("confidence").asDouble()));
            if (question instanceof YesNoQuestion) {
                answers.put(name, new YesNoAnswer("yes".equals(value) ? confidence : 1.0 - confidence));
            } else if (question instanceof ChoiceQuestion choice) {
                if (!choice.criteria().containsKey(value)) {
                    throw new ProviderProtocolException("Answer for " + name + " is not an offered option");
                }
                var keys = List.copyOf(choice.criteria().keySet());
                var rest = keys.size() > 1 ? (1.0 - confidence) / (keys.size() - 1) : 0.0;
                var probabilities = new LinkedHashMap<String, Double>();
                keys.forEach(key -> probabilities.put(key, key.equals(value) ? confidence : rest));
                answers.put(name, new ChoiceAnswer(value, confidence, probabilities));
            }
        });
        return answers;
    }

    private String creditKey() {
        return "decision|" + id() + "|" + properties.baseUrl();
    }

    private RuntimeException translateTransportFailure(RestClientException e) {
        var cause = e.getCause();
        while (cause != null) {
            if (cause instanceof HttpTimeoutException || cause instanceof HttpConnectTimeoutException) {
                return new ProviderTimeoutException("Provider did not respond in time");
            }
            cause = cause.getCause();
        }
        return new ProviderUnavailableException("Provider is unreachable", e);
    }
}
