package com.rivell.workbench.intelligence.provider;

import com.fasterxml.jackson.databind.JsonNode;

import com.rivell.workbench.intelligence.ChoiceAnswer;
import com.rivell.workbench.intelligence.ChoiceQuestion;
import com.rivell.workbench.intelligence.ProviderProtocolException;
import com.rivell.workbench.intelligence.RatingAnswer;
import com.rivell.workbench.intelligence.RatingQuestion;
import com.rivell.workbench.intelligence.SemanticAnswer;
import com.rivell.workbench.intelligence.SemanticQuestion;
import com.rivell.workbench.intelligence.YesNoAnswer;
import com.rivell.workbench.intelligence.YesNoQuestion;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Converts a TypeSafe answer payload into the provider-neutral answer for the matching
 * question. Strict by design: wrong primitive, unknown choice label, or malformed
 * probability is a protocol failure, never silently coerced.
 */
final class JevAnswerMapper {

    private static final String TYPE_NOUL = "noul";
    private static final String TYPE_CHOICE = "choice";
    private static final String TYPE_SCORE = "score";

    private JevAnswerMapper() {
    }

    static SemanticAnswer map(String questionName, SemanticQuestion question, JsonNode answer) {
        if (question instanceof YesNoQuestion) {
            return yesNo(questionName, answer);
        }
        if (question instanceof ChoiceQuestion choiceQuestion) {
            return choice(questionName, choiceQuestion, answer);
        }
        if (question instanceof RatingQuestion ratingQuestion) {
            return rating(questionName, ratingQuestion, answer);
        }
        throw new ProviderProtocolException("Unsupported question primitive for " + questionName);
    }

    private static SemanticAnswer yesNo(String questionName, JsonNode answer) {
        requireType(questionName, answer, TYPE_NOUL);
        return new YesNoAnswer(requireProbability(questionName, "noul", answer.get("noul")));
    }

    private static SemanticAnswer choice(String questionName, ChoiceQuestion question, JsonNode answer) {
        requireType(questionName, answer, TYPE_CHOICE);
        var probabilities = probabilityMap(questionName, answer.get("probabilities"));
        var criteriaKeys = question.criteria().keySet();
        var probabilityKeys = probabilities.keySet();
        for (var key : probabilityKeys) {
            if (!criteriaKeys.contains(key)) {
                throw new ProviderProtocolException(
                        "Answer for " + questionName + " contains unknown choice label " + key);
            }
        }
        for (var key : criteriaKeys) {
            if (!probabilityKeys.contains(key)) {
                throw new ProviderProtocolException(
                        "Answer for " + questionName + " is missing probability for choice " + key);
            }
        }
        var selected = answer.path("choice").asText(null);
        if (selected == null || selected.isBlank()) {
            throw new ProviderProtocolException("Answer for " + questionName + " has no selected choice");
        }
        if (!criteriaKeys.contains(selected)) {
            throw new ProviderProtocolException(
                    "Answer for " + questionName + " selected unknown choice " + selected);
        }
        var confidence = answer.get("confidence");
        if (confidence == null || !confidence.isNumber()) {
            throw new ProviderProtocolException("Answer for " + questionName + " has no numeric confidence");
        }
        return new ChoiceAnswer(selected, confidence.asDouble(), probabilities);
    }

    private static SemanticAnswer rating(String questionName, RatingQuestion question, JsonNode answer) {
        requireType(questionName, answer, TYPE_SCORE);
        var probabilities = probabilityMap(questionName, answer.get("probabilities"));
        var expectedKeys = IntStream.range(0, question.levels().size())
                .mapToObj(String::valueOf)
                .collect(java.util.stream.Collectors.toSet());
        if (!expectedKeys.equals(probabilities.keySet())) {
            throw new ProviderProtocolException(
                    "Answer for " + questionName + " has level probabilities that do not match the rubric");
        }
        var scoreNode = answer.get("score");
        if (scoreNode == null || !scoreNode.isNumber()) {
            throw new ProviderProtocolException("Answer for " + questionName + " has no numeric score");
        }
        var confidence = answer.get("confidence");
        if (confidence == null || !confidence.isNumber()) {
            throw new ProviderProtocolException("Answer for " + questionName + " has no numeric confidence");
        }
        return new RatingAnswer(
                scoreNode.asDouble(), confidence.asDouble(),
                probabilities.entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                                (a, b) -> a, LinkedHashMap::new)));
    }

    private static Map<String, Double> probabilityMap(String questionName, JsonNode node) {
        if (node == null || !node.isObject() || node.isEmpty()) {
            throw new ProviderProtocolException("Answer for " + questionName + " has no probabilities");
        }
        var probabilities = new LinkedHashMap<String, Double>();
        var fields = node.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            probabilities.put(field.getKey(), requireProbability(questionName, field.getKey(), field.getValue()));
        }
        return probabilities;
    }

    private static double requireProbability(String questionName, String key, JsonNode node) {
        if (node == null || !node.isNumber()
                || node.doubleValue() < 0.0 || node.doubleValue() > 1.0) {
            throw new ProviderProtocolException(
                    "Answer for " + questionName + " has malformed probability " + key);
        }
        return node.doubleValue();
    }

    private static void requireType(String questionName, JsonNode answer, String expectedType) {
        if (answer == null || !answer.isObject()) {
            throw new ProviderProtocolException(
                    "Answer for " + questionName + " is not an object");
        }
        var type = answer.path("type").asText(null);
        if (!expectedType.equals(type)) {
            throw new ProviderProtocolException(
                    "Answer for " + questionName + " has primitive " + type + ", expected " + expectedType);
        }
    }
}
