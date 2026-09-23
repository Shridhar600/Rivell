package com.rivell.workbench.intelligence;

import java.util.Map;

/**
 * Named independent questions sharing one state, evaluated together in a single
 * provider execution. Map keys make duplicate names structurally impossible.
 */
public record QuestionPack(Map<String, SemanticQuestion> questions) {

    public QuestionPack {
        if (questions == null || questions.isEmpty()) {
            throw new IllegalArgumentException("A question pack requires at least one question");
        }
        questions = Map.copyOf(questions);
    }
}
