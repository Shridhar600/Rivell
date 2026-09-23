package com.rivell.workbench.intelligence.provider;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * OpenAI Chat Completions wire DTOs (also served compatibly by OpenRouter and the
 * Vercel AI Gateway). Strict mode schema constraints: every property required,
 * additionalProperties false.
 */
final class OpenAiCompatibleDtos {

    private OpenAiCompatibleDtos() {
    }

    record ChatRequest(
            String model,
            List<Message> messages,
            double temperature,
            ResponseFormat responseFormat) {

        record Message(String role, String content) {
        }

        record ResponseFormat(ResponseFormatJsonSchema jsonSchema) {
        }

        record ResponseFormatJsonSchema(String name, boolean strict, SchemaWrapper schema) {

            record SchemaWrapper(String type, Map<String, Object> properties,
                    List<String> required, boolean additionalProperties) {
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChatResponse(
            String model,
            List<Choice> choices,
            Usage usage) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Choice(Message message,
                @JsonProperty("finish_reason") String finishReason) {

            @JsonIgnoreProperties(ignoreUnknown = true)
            record Message(String role, String content, String refusal) {
            }
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Usage(@JsonProperty("prompt_tokens") Integer promptTokens,
                @JsonProperty("completion_tokens") Integer completionTokens) {
        }
    }
}
