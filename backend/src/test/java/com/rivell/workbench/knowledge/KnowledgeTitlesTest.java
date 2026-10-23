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

    @Test
    void aLegalAbbreviationIsNotASentenceBoundary() {
        var paragraph = "The notice period in Sec. 4 must be at least thirty days, and the "
                + "Supplier must defend all third-party claims, and the parties must cooperate "
                + "in the defence of the claim.";

        var title = KnowledgeTitles.bounded(paragraph);

        assertThat(title).startsWith("The notice period in Sec. 4")
                .doesNotEndWith("Sec.");
    }

    @Test
    void anInlineAbbreviationIsNotASentenceBoundary() {
        var paragraph = "The Supplier shall, e.g. where the Customer requires it, defend all "
                + "third-party claims and pay any damages awarded, and the parties shall cooperate "
                + "in the defence of the claim at all times.";

        var title = KnowledgeTitles.bounded(paragraph);

        assertThat(title).isNotEqualTo("The Supplier shall, e.g.")
                .startsWith("The Supplier shall, e.g. where");
    }

    @Test
    void aNumberedFirstLineIsSkippedForTheTextBelowIt() {
        var paragraph = "1.\nThe Supplier shall defend and indemnify the Customer against all "
                + "third-party claims that the Software infringes any intellectual property right.";

        var title = KnowledgeTitles.bounded(paragraph);

        assertThat(title).isNotEqualTo("1.").startsWith("The Supplier shall defend");
    }

    @Test
    void aNumberOnlyPassageHasNoTitle() {
        assertThat(KnowledgeTitles.bounded("2026")).isNull();
        assertThat(KnowledgeTitles.bounded("1.\n2.\n")).isNull();
    }

    @Test
    void aCutNeverLeavesASingleLetter() {
        var title = KnowledgeTitles.bounded("A " + "x".repeat(200));

        assertThat(title).isNotEqualTo("A…");
        assertThat(title.length()).isLessThanOrEqualTo(KnowledgeTitles.MAX_LENGTH);
    }
}
