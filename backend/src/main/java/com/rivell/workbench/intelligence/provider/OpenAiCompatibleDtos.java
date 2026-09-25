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

    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    record ChatRequest(
            String model,
            List<Message> messages,
            double temperature,
            @JsonProperty("response_format") ResponseFormat responseFormat) {

        record Message(String role, String content) {
        }

        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        record ResponseFormat(
                @JsonProperty("json_schema") ResponseFormatJsonSchema jsonSchema,
                @JsonProperty("type") String type) {

            static ResponseFormat strictSchema(ResponseFormatJsonSchema jsonSchema) {
                return new ResponseFormat(jsonSchema, "json_schema");
            }

            static ResponseFormat jsonObject() {
                return new ResponseFormat(null, "json_object");
            }
        }

        record ResponseFormatJsonSchema(String name, boolean strict, SchemaWrapper schema) {
            // name/strict/schema field names match the wire contract

            record SchemaWrapper(String type, Map<String, Object> properties,
                    List<String> required, boolean additionalProperties) {
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChatResponse(
            String model,
            List<Choice> choices,
            Usage usage,
            @JsonProperty("error") ErrorBody error) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        record ErrorBody(String type, String code) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Choice(Message message,
                @JsonProperty("finish_reason") String finishReason) {

            @JsonIgnoreProperties(ignoreUnknown = true)
            record Message(String role, String content, String refusal) {
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Usage(@JsonProperty("prompt_tokens") Integer promptTokens,
            @JsonProperty("completion_tokens") Integer completionTokens) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmbeddingResponse(
            String model,
            List<EmbeddingItem> data,
            Usage usage) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        record EmbeddingItem(Integer index, List<Double> embedding) {
        }
    }
}
