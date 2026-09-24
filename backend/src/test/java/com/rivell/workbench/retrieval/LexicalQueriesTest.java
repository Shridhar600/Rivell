package com.rivell.workbench.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LexicalQueriesTest {

    @Test
    void orTermsAreDedupedSingleCharsDroppedAndBounded() {
        var text = "liability liability a 6.1 fees FEES claim x";
        assertThat(LexicalQueries.orTerms(text))
                .isEqualTo("liability OR 61 OR fees OR claim");
    }

    @Test
    void termCountIsCapped() {
        var text = "a".repeat(1);
        var many = String.join(" ",
                java.util.stream.IntStream.rangeClosed(1, 60)
                        .mapToObj(i -> "term" + i).toList());
        var query = LexicalQueries.orTerms(many + " " + text);
        assertThat(query.split(" OR ")).hasSize(32);
        assertThat(query).doesNotContain("term60").endsWith(" OR term32");
    }
}
