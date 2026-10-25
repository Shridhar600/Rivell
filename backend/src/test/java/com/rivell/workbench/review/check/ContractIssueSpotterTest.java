package com.rivell.workbench.review.check;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.rivell.workbench.review.IssueSpottingSpecs;

/**
 * The whole-contract read's output contract: one issue per finding, attached to the clause that
 * contains the issue's own quote. A quote that cannot be found is dropped, never guessed at.
 */
class ContractIssueSpotterTest {

    private static final UUID EXECUTION = UUID.randomUUID();

    private static final String PAYMENT =
            "The Customer shall pay each valid invoice within sixty (60) days of receipt.";
    private static final String TERMINATION =
            "Either party may terminate for material breach not remedied within fourteen (14) days.";
    private static final String GOVERNING_LAW =
            "The laws of New South Wales and the courts of Sydney govern this agreement.";

    private static final List<IssueSpottingSpecs.SpottingClause> CLAUSES = List.of(
            clause(1, "Payment", PAYMENT),
            clause(2, "Termination", TERMINATION),
            clause(3, "Governing law", GOVERNING_LAW));

    @Test
    void oneIssuePerItemIsAttachedToTheClauseContainingItsQuote() {
        var output = String.join("\n",
                "3 | HIGH | " + TERMINATION + " | termination is one-sided",
                "1 | MEDIUM | " + PAYMENT + " | payment is slow");

        var parsed = ContractIssueSpotter.parse(output, CLAUSES, EXECUTION);

        assertThat(parsed.dropped()).isZero();
        assertThat(parsed.spots()).containsOnlyKeys(2, 1);
        assertThat(parsed.spots().get(2)).singleElement()
                .satisfies(spot -> {
                    assertThat(spot.severity()).isEqualTo("HIGH");
                    assertThat(spot.reason()).isEqualTo("termination is one-sided");
                    assertThat(spot.quote()).isEqualTo(TERMINATION);
                });
        assertThat(parsed.spots().get(1)).singleElement()
                .satisfies(spot -> assertThat(spot.reason()).isEqualTo("payment is slow"));
        // The model named clause 3, but the quote lives in clause 2, so no issue is attached to 3.
        assertThat(parsed.spots()).doesNotContainKey(3);
    }

    @Test
    void quoteMatchingIgnoresWhitespaceSmartQuotesAndClauseNumbering() {
        var withApostrophe = List.of(clause(7,
                "Liability",
                "The Supplier\u2019s aggregate liability is limited to the fees paid in the twelve (12) months before the claim."));
        var output = "7 | medium | 7.2   The Supplier's aggregate liability is limited"
                + " \t  to the fees paid in the twelve (12) months before the claim."
                + " | liability is capped too low";

        var parsed = ContractIssueSpotter.parse(output, withApostrophe, EXECUTION);

        assertThat(parsed.dropped()).isZero();
        assertThat(parsed.spots()).containsOnlyKeys(7);
        assertThat(parsed.spots().get(7)).singleElement()
                .satisfies(spot -> assertThat(spot.severity()).isEqualTo("MEDIUM"));
    }

    @Test
    void quoteMatchingToleratesDashes() {
        var clauses = List.of(clause(1,
                "Termination",
                "The Supplier may terminate \u2014 without notice \u2014 for material breach."));
        var output = "1 | HIGH | The Supplier may terminate - without notice - for material breach. | notice is not required";

        var parsed = ContractIssueSpotter.parse(output, clauses, EXECUTION);

        assertThat(parsed.dropped()).isZero();
        assertThat(parsed.spots()).containsOnlyKeys(1);
    }

    @Test
    void quoteMatchingToleratesSmartDoubleQuotes() {
        var clauses = List.of(clause(1,
                "Confidentiality",
                "The \u201CConfidential Information\u201D definition covers marked information."));
        var output = "1 | HIGH | The \"Confidential Information\" definition covers marked information. | definition is too wide";

        var parsed = ContractIssueSpotter.parse(output, clauses, EXECUTION);

        assertThat(parsed.dropped()).isZero();
        assertThat(parsed.spots()).containsOnlyKeys(1);
    }

    @Test
    void quoteMatchingToleratesListNumbering() {
        var clauses = List.of(clause(1,
                "Payment",
                "The Supplier shall pay each valid invoice within sixty (60) days."));
        var output = "1 | HIGH | (a) The Supplier shall pay each valid invoice within sixty (60) days. | payment is late";

        var parsed = ContractIssueSpotter.parse(output, clauses, EXECUTION);

        assertThat(parsed.dropped()).isZero();
        assertThat(parsed.spots()).containsOnlyKeys(1);
    }

    @Test
    void quoteMatchingToleratesAKeywordListNumber() {
        var clauses = List.of(clause(7,
                "Payment",
                "The Supplier shall pay each valid invoice within sixty (60) days."));
        var output = "7 | HIGH | Section 7 The Supplier shall pay each valid invoice within sixty (60) days. | payment is late";

        var parsed = ContractIssueSpotter.parse(output, clauses, EXECUTION);

        assertThat(parsed.dropped()).isZero();
        assertThat(parsed.spots()).containsOnlyKeys(7);
    }

    @Test
    void aParentheticalWordIsNotAListLabel() {
        var clauses = List.of(clause(1,
                "Indemnity",
                "Supplier shall indemnify the Customer against third-party claims."));
        var output = "1 | HIGH | (the) Supplier shall indemnify the Customer against third-party claims. | indemnity is narrow";

        var parsed = ContractIssueSpotter.parse(output, clauses, EXECUTION);

        assertThat(parsed.spots()).isEmpty();
        assertThat(parsed.dropped()).isEqualTo(1);
    }

    @Test
    void quoteMatchingToleratesAlphabeticAndRomanListNumbering() {
        var clauses = List.of(clause(1,
                "Payment",
                "The Supplier shall pay each valid invoice within sixty (60) days."));

        var alphabetic = ContractIssueSpotter.parse(
                "1 | HIGH | a. The Supplier shall pay each valid invoice within sixty (60) days. | payment is late",
                clauses, EXECUTION);
        var roman = ContractIssueSpotter.parse(
                "1 | HIGH | i. The Supplier shall pay each valid invoice within sixty (60) days. | payment is late",
                clauses, EXECUTION);

        assertThat(alphabetic.dropped()).isZero();
        assertThat(alphabetic.spots()).containsOnlyKeys(1);
        assertThat(roman.dropped()).isZero();
        assertThat(roman.spots()).containsOnlyKeys(1);
    }

    @Test
    void aLabelAndATrailingPeriodAreBothTolerated() {
        var clauses = List.of(clause(7, "Payment", "The Supplier shall pay each valid invoice within sixty (60) days"));
        var output = "7 | HIGH | 7.2 The Supplier shall pay each valid invoice within sixty (60) days. | payment is late";

        var parsed = ContractIssueSpotter.parse(output, clauses, EXECUTION);

        assertThat(parsed.dropped()).isZero();
        assertThat(parsed.spots()).containsOnlyKeys(7);
    }

    @Test
    void aNumberThatIsNotClauseNumberingIsNotStripped() {
        var clauses = List.of(
                clause(3, "Payment", "The Customer shall pay each valid invoice within 60 days of receipt."),
                clause(4, "Liability", "The cap is 10 percent of the list price."));
        var output = String.join("\n",
                "3 | HIGH | 30 days of receipt | payment is late",
                "4 | HIGH | 12.5 percent of the list price | cap is too low");

        var parsed = ContractIssueSpotter.parse(output, clauses, EXECUTION);

        assertThat(parsed.spots()).isEmpty();
        assertThat(parsed.dropped()).isEqualTo(2);
    }

    @Test
    void aQuoteWithoutAWordIsNotGrounded() {
        for (var quote : List.of(".", "...", "\u2014", "60")) {
            var parsed = ContractIssueSpotter.parse(
                    "3 | HIGH | " + quote + " | no words were quoted", CLAUSES, EXECUTION);
            assertThat(parsed.spots()).as("quote " + quote).isEmpty();
            assertThat(parsed.dropped()).as("quote " + quote).isEqualTo(1);
        }
    }

    @Test
    void anIssueShapedLineWithADecimalNumberOrNoNumberIsCounted() {
        var withDecimal = ContractIssueSpotter.parse("7.2 | HIGH |", CLAUSES, EXECUTION);
        var withoutNumber = ContractIssueSpotter.parse("| HIGH | quote | reason", CLAUSES, EXECUTION);

        assertThat(withDecimal.spots()).isEmpty();
        assertThat(withDecimal.dropped()).isEqualTo(1);
        assertThat(withoutNumber.spots()).isEmpty();
        assertThat(withoutNumber.dropped()).isEqualTo(1);
    }

    @Test
    void aClauseNumberTooLargeToParseIsDroppedAndTheGoodLinesSurvive() {
        var output = String.join("\n",
                "999999999999999999999 | HIGH | " + PAYMENT + " | payment is slow",
                "2 | HIGH | " + TERMINATION + " | termination is one-sided");

        var parsed = ContractIssueSpotter.parse(output, CLAUSES, EXECUTION);

        assertThat(parsed.spots()).containsOnlyKeys(2);
        assertThat(parsed.spots().get(2)).singleElement()
                .satisfies(spot -> assertThat(spot.reason()).isEqualTo("termination is one-sided"));
        assertThat(parsed.dropped()).isEqualTo(1);
    }

    @Test
    void anIssueWhoseQuoteIsNotFoundIsDroppedAndCounted() {
        var output = String.join("\n",
                "1 | HIGH | " + PAYMENT + " | payment is slow",
                "2 | HIGH | words that appear in no clause | invented issue");

        var parsed = ContractIssueSpotter.parse(output, CLAUSES, EXECUTION);

        assertThat(parsed.spots()).containsOnlyKeys(1);
        assertThat(parsed.dropped()).isEqualTo(1);
    }

    @Test
    void aLineHoldingSeveralIssuesIsNotTurnedIntoOneFinding() {
        // The reported failure: six issues concatenated into one line. It must not become one
        // finding that holds all six, and every dropped issue is counted.
        var output = "3 | MEDIUM | Payment terms may be unfavorable if fees are delayed. "
                + "6 | MEDIUM | Change order process could limit flexibility. "
                + "8.2 | HIGH | Unlimited revisions could lead to excessive costs.";
        var parsed = ContractIssueSpotter.parse(output, CLAUSES, EXECUTION);

        assertThat(parsed.spots()).isEmpty();
        assertThat(parsed.dropped()).isEqualTo(3);
    }

    @Test
    void aLineThatNamesAnIssueButHasNoQuoteIsCountedAndDropped() {
        var parsed = ContractIssueSpotter.parse("3 | HIGH |", CLAUSES, EXECUTION);

        assertThat(parsed.spots()).isEmpty();
        assertThat(parsed.dropped()).isEqualTo(1);
    }

    @Test
    void proseBeforeTheIssuesIsNotCountedAsADroppedIssue() {
        var output = "Here are the issues:\n3 | HIGH | " + GOVERNING_LAW + " | governing law is foreign";
        var parsed = ContractIssueSpotter.parse(output, CLAUSES, EXECUTION);

        assertThat(parsed.spots()).containsOnlyKeys(3);
        assertThat(parsed.dropped()).isZero();
    }

    @Test
    void aRepeatedLineDoesNotBecomeTwoFindings() {
        var line = "3 | HIGH | " + GOVERNING_LAW + " | governing law is foreign";
        var parsed = ContractIssueSpotter.parse(line + "\n" + line, CLAUSES, EXECUTION);

        assertThat(parsed.dropped()).isZero();
        assertThat(parsed.spots()).containsOnlyKeys(3);
        assertThat(parsed.spots().get(3)).hasSize(1);
    }

    @Test
    void aSuccessfulReadThatNamesNothingCreatesNoIssue() {
        var parsed = ContractIssueSpotter.parse("NONE", CLAUSES, EXECUTION);

        assertThat(parsed.spots()).isEmpty();
        assertThat(parsed.dropped()).isZero();
    }

    private static IssueSpottingSpecs.SpottingClause clause(int number, String heading, String text) {
        return new IssueSpottingSpecs.SpottingClause(number, heading, text);
    }
}
