package com.rivell.workbench.intelligence.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rivell.workbench.intelligence.ChoiceAnswer;
import com.rivell.workbench.intelligence.ChoiceQuestion;
import com.rivell.workbench.intelligence.DecisionExecutionRequest;
import com.rivell.workbench.intelligence.ProviderProtocolException;
import com.rivell.workbench.intelligence.QuestionPack;
import com.rivell.workbench.intelligence.SemanticQuestion;
import com.rivell.workbench.intelligence.SemanticState;
import com.rivell.workbench.intelligence.YesNoAnswer;
import com.rivell.workbench.intelligence.YesNoQuestion;

class OpenAiCompatibleDecisionProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GenerativeProperties properties = new GenerativeProperties(
            "openai-compatible", "https://api.example.test/v1", "test-key", "gpt-test",
            null, null, Duration.ofSeconds(1), Duration.ofSeconds(1));

    private DecisionExecutionRequest request() {
        var questions = new LinkedHashMap<String, SemanticQuestion>();
        questions.put("needs_review", new YesNoQuestion("Is the clause unusual and against us?",
                "It restricts us.", "It is normal."));
        questions.put("position_1", new ChoiceQuestion("How does the clause compare?",
                Map.of("MEETS_PREFERRED", "Consistent.", "WALK_AWAY", "Forbidden.", "UNCLEAR", "Unclear.")));
        SemanticState state = new SemanticState() {
            @Override
            public int schemaVersion() {
                return 1;
            }

            @Override
            public Object payload() {
                return Map.of("clause", "The only remedy is damages.");
            }
        };
        return new DecisionExecutionRequest("gpt-test", state, new QuestionPack(questions));
    }

    private static String response(String content) {
        return """
                {"model":"gpt-test-2026","choices":[{"message":{"role":"assistant","content":%s},
                "finish_reason":"stop"}],"usage":{"prompt_tokens":120,"completion_tokens":30}}
                """.formatted(new com.fasterxml.jackson.databind.node.TextNode(content).toString());
    }

    @Test
    void asksEveryQuestionUnderAStrictSchemaAndMapsConfidenceToProbability() throws Exception {
        var sent = new AtomicReference<String>();
        var provider = new OpenAiCompatibleDecisionProvider((path, payload) -> {
            sent.set(payload);
            return response("""
                    {"needs_review":{"answer":"no","confidence":0.9},
                     "position_1":{"answer":"WALK_AWAY","confidence":0.8}}""");
        }, objectMapper, properties);

        var result = provider.execute(request());

        var body = objectMapper.readTree(sent.get());
        assertThat(body.path("model").asText()).isEqualTo("gpt-test");
        var schema = body.path("response_format").path("json_schema");
        assertThat(schema.path("strict").asBoolean()).isTrue();
        assertThat(schema.path("schema").path("required").toString())
                .contains("needs_review").contains("position_1");
        assertThat(schema.path("schema").path("properties").path("position_1").path("properties")
                .path("answer").path("enum").toString()).contains("WALK_AWAY").contains("UNCLEAR");
        assertThat(result.answers().get("needs_review")).isInstanceOfSatisfying(YesNoAnswer.class,
                yesNo -> assertThat(yesNo.probability()).isCloseTo(0.1, org.assertj.core.data.Offset.offset(1e-9)));
        assertThat(result.answers().get("position_1")).isInstanceOfSatisfying(ChoiceAnswer.class, choice -> {
            assertThat(choice.selected()).isEqualTo("WALK_AWAY");
            assertThat(choice.probabilities()).containsEntry("WALK_AWAY", 0.8);
            assertThat(choice.probabilities().get("UNCLEAR")).isCloseTo(0.1, org.assertj.core.data.Offset.offset(1e-9));
        });
        assertThat(result.usage().inputTokens()).isEqualTo(120);
    }

    @Test
    void anAnswerOutsideTheOfferedOptionsIsAProtocolError() {
        var provider = new OpenAiCompatibleDecisionProvider((path, payload) -> response("""
                {"needs_review":{"answer":"yes","confidence":0.7},
                 "position_1":{"answer":"MAYBE","confidence":0.8}}"""), objectMapper, properties);

        assertThatThrownBy(() -> provider.execute(request()))
                .isInstanceOf(ProviderProtocolException.class)
                .hasMessageContaining("not an offered option");
    }
}
