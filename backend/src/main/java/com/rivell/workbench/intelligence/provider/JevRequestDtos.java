package com.rivell.workbench.intelligence.provider;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * TypeSafe request DTOs (internal to the adapter). Field names follow the provider API.
 */
final class JevRequestDtos {

    private JevRequestDtos() {
    }

    record SystemOneRequest(
            String model,
            Object state,
            Map<String, Object> questions) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record NoulQuestion(String type, String instructions,
            @JsonProperty("criteria") NoulCriteria criteria) {

        NoulQuestion(String instructions, String trueCriterion, String falseCriterion) {
            this("noul", instructions,
                    trueCriterion == null && falseCriterion == null
                            ? null
                            : new NoulCriteria(trueCriterion, falseCriterion));
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record NoulCriteria(String trueCriterion, String falseCriterion) {

        @JsonProperty("true")
        String trueValue() {
            return trueCriterion;
        }

        @JsonProperty("false")
        String falseValue() {
            return falseCriterion;
        }
    }

    record ChoiceQuestion(String type, String instructions, java.util.LinkedHashMap<String, String> criteria) {

        ChoiceQuestion(String instructions, java.util.LinkedHashMap<String, String> criteria) {
            this("choice", instructions, criteria);
        }
    }

    record ScoreQuestion(String type, String instructions, List<String> criteria) {

        ScoreQuestion(String instructions, List<String> criteria) {
            this("score", instructions, criteria);
        }
    }

    record SystemOneResponse(
            String model,
            Map<String, com.fasterxml.jackson.databind.JsonNode> answers,
            Usage usage) {
    }

    record Usage(
            @JsonProperty("input_tokens") Integer inputTokens,
            @JsonProperty("output_tokens") Integer outputTokens) {
    }

    record ModelMetadata(String name, String description, @JsonProperty("release_date") String releaseDate) {
    }

    record ModelMetadataList(java.util.List<ModelMetadata> models) {
    }
}
