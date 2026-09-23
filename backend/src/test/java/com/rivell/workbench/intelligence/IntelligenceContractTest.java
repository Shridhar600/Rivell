package com.rivell.workbench.intelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class IntelligenceContractTest {

    @Test
    void yesNoQuestionAcceptsOptionalCriteria() {
        var question = new YesNoQuestion("Is this a confidentiality clause?", null, null);

        assertThat(question.instructions()).contains("confidentiality");
        assertThat(question.trueCriterion()).isNull();
    }

    @Test
    void choiceQuestionRequiresAtLeastOneCriterionAndKeepsOrder() {
        var criteria = new LinkedHashMap<String, String>();
        criteria.put("first", "applies first");
        criteria.put("second", "applies second");
        var question = new ChoiceQuestion("Which applies?", criteria);

        assertThat(question.criteria().keySet()).containsExactly("first", "second");
    }

    @Test
    void choiceQuestionRejectsEmptyCriteria() {
        assertThatThrownBy(() -> new ChoiceQuestion("Which applies?", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ratingQuestionAcceptsTwoToTenLevels() {
        var question = new RatingQuestion("How severe?", List.of("low", "high"));

        assertThat(question.levels()).hasSize(2);
    }

    @Test
    void ratingQuestionRejectsTooFewOrTooManyLevels() {
        assertThatThrownBy(() -> new RatingQuestion("How severe?", List.of("only")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new RatingQuestion("How severe?",
                List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void yesNoAnswerValidatesProbabilityRange() {
        assertThat(new YesNoAnswer(0.0).probability()).isZero();
        assertThat(new YesNoAnswer(1.0).probability()).isEqualTo(1.0);

        assertThatThrownBy(() -> new YesNoAnswer(-0.1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new YesNoAnswer(1.1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new YesNoAnswer(Double.NaN)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void choiceAnswerValidatesConfidenceAndProbabilities() {
        var answer = new ChoiceAnswer("billing", 0.9, Map.of("billing", 0.8, "other", 0.2));

        assertThat(answer.selected()).isEqualTo("billing");

        assertThatThrownBy(() -> new ChoiceAnswer("billing", 1.5, Map.of("billing", 1.0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChoiceAnswer("billing", 0.9, Map.of("billing", 1.2)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChoiceAnswer("billing", 0.9, Map.of("billing", Double.NaN)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ratingAnswerValidatesScoreAndConfidence() {
        var answer = new RatingAnswer(1.43, 0.9, Map.of("0", 0.1, "1", 0.47, "2", 0.43));

        assertThat(answer.expectedScore()).isEqualTo(1.43);

        assertThatThrownBy(() -> new RatingAnswer(3.5, 0.9, Map.of("0", 1.0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RatingAnswer(-0.5, 0.9, Map.of("0", 1.0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void questionPackRequiresAtLeastOneNamedQuestion() {
        var pack = new QuestionPack(Map.of(
                "mentions_confidentiality", new YesNoQuestion("Is this confidentiality?", null, null)));

        assertThat(pack.questions()).hasSize(1);

        assertThatThrownBy(() -> new QuestionPack(Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
