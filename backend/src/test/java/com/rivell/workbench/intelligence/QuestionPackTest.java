package com.rivell.workbench.intelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.AbstractMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class QuestionPackTest {

    private static YesNoQuestion question(String name) {
        return new YesNoQuestion("Question " + name + "?", "True for " + name, "False for " + name);
    }

    /**
     * A Map with no iteration guarantee, presented in an order that is not the sorted order.
     * The old copy iterated by hash, which this test saw as charlie, alpha, delta.
     */
    private static Map<String, SemanticQuestion> unorderedQuestions() {
        var entries = new LinkedHashSet<Map.Entry<String, SemanticQuestion>>();
        entries.add(Map.entry("delta", question("delta")));
        entries.add(Map.entry("alpha", question("alpha")));
        entries.add(Map.entry("charlie", question("charlie")));
        return new AbstractMap<>() {
            @Override
            public Set<Entry<String, SemanticQuestion>> entrySet() {
                return entries;
            }
        };
    }

    @Test
    void unorderedQuestionSourcesAreSortedAndUnmodifiable() {
        var pack = new QuestionPack(unorderedQuestions());

        assertThat(pack.questions().keySet()).containsExactly("alpha", "charlie", "delta");
        assertThatThrownBy(() -> pack.questions().put("b", question("b")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void orderedQuestionSourcesKeepTheCallersOrder() {
        var questions = new LinkedHashMap<String, SemanticQuestion>();
        questions.put("z", question("z"));
        questions.put("a", question("a"));

        assertThat(new QuestionPack(questions).questions().keySet())
                .containsExactly("z", "a");
    }
}
