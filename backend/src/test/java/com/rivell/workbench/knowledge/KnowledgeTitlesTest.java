package com.rivell.workbench.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KnowledgeTitlesTest {

    @Test
    void aShortTitleIsKeptAsWritten() {
        assertThat(KnowledgeTitles.bounded("Term and survival")).isEqualTo("Term and survival");
    }

    @Test
    void aMultiLineTextIsTitledByItsFirstLine() {
        assertThat(KnowledgeTitles.bounded("DO NOT TRADE THIS BOUNDARY FOR SPEED\nNo live data may enter."))
                .isEqualTo("DO NOT TRADE THIS BOUNDARY FOR SPEED");
    }

    @Test
    void aLongParagraphIsTitledByItsFirstSentence() {
        var paragraph = "Use a definition that covers information marked confidential. "
                + "It should also cover demonstrations and oral discussions as well as files, "
                + "and a practical marking rule is useful for information whose sensitivity is not "
                + "apparent, but do not let a marking rule strip protection from source code.";

        assertThat(KnowledgeTitles.bounded(paragraph))
                .isEqualTo("Use a definition that covers information marked confidential.");
    }

    @Test
    void aLongSentenceIsCutAtAWordAndMarked() {
        var sentence = "word ".repeat(60).strip();

        var title = KnowledgeTitles.bounded(sentence);

        assertThat(title.length()).isLessThanOrEqualTo(KnowledgeTitles.MAX_LENGTH);
        assertThat(title).endsWith("…").doesNotContain("  ").doesNotEndWith(" …");
    }

    @Test
    void blankTextStaysBlank() {
        assertThat(KnowledgeTitles.bounded("  ")).isEqualTo("  ");
        assertThat(KnowledgeTitles.bounded(null)).isNull();
    }
}
