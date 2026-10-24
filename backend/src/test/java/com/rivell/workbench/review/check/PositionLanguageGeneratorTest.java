package com.rivell.workbench.review.check;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;


/** Shape checks on machine wording, with cases taken from real generated proposals. */
class PositionLanguageGeneratorTest {

    private static final String TERM = "This Agreement commences on the Effective Date and continues for an "
            + "initial period of twenty-four (24) months, and renews for successive periods of twelve (12) "
            + "months unless either Party gives notice of non-renewal at least ninety (90) days before the "
            + "end of the then-current term.";

    @Test
    void aMinimalEditIsAProposalButAnUnchangedClauseIsNot() {
        var minimal = TERM.replace("twenty-four (24)", "twelve (12)");
        assertThat(PositionLanguageGenerator.valid(minimal, "Initial term cut to 12 months.", TERM)).isTrue();

        var unchanged = "  " + TERM.replace(", and", " and") + "\n";
        assertThat(PositionLanguageGenerator.valid(unchanged, "No change.", TERM)).isFalse();
    }

    @Test
    void editingInstructionsAndLabelsAreNotContractText() {
        assertThat(PositionLanguageGenerator.valid(
                "Replace Sections 3.2 and 3.3 with:\n3.2 The Recipient may disclose Confidential "
                        + "Information where the Discloser has approved it in writing.", "x", TERM)).isFalse();
        assertThat(PositionLanguageGenerator.valid(
                "Candidate language (not approved): Aurellian grants Nordvale a perpetual licence.",
                "x", TERM)).isFalse();
        assertThat(PositionLanguageGenerator.valid(
                "The cap does not apply to clinical harm. Delete the final sentence of the existing "
                        + "clause 10.B.", "x", TERM)).isFalse();
        assertThat(PositionLanguageGenerator.valid(
                "This Section 5 is deleted in its entirety. No right is granted to use Confidential "
                        + "Information retained in unaided memory.", "x", TERM)).isTrue();
    }

    @Test
    void aRepeatedHeadingIsDroppedBecauseTheHeadingStaysInTheDocument() {
        assertThat(PositionLanguageGenerator.withoutHeading(
                "5. Residual Knowledge. No right is granted to use residual knowledge.",
                "5. Residual Knowledge."))
                .isEqualTo("No right is granted to use residual knowledge.");
        assertThat(PositionLanguageGenerator.withoutHeading("No right is granted.", "5. Residual Knowledge."))
                .isEqualTo("No right is granted.");
    }

    @Test
    void theBodyOfARunInClauseIsTheTextAfterItsHeading() {
        var paragraph = "5. Residual Knowledge. The Recipient may use information retained in unaided memory.";
        assertThat(PositionLanguageGenerator.body(paragraph, paragraph, "5. Residual Knowledge."))
                .isEqualTo("The Recipient may use information retained in unaided memory.");
        assertThat(PositionLanguageGenerator.body("Term\n" + TERM, "Term", null)).isEqualTo(TERM);
    }

    @Test
    void outOfCreditIsNotRecordedAsAnOrdinaryRejection() {
        var generation = new com.rivell.workbench.intelligence.GenerativeReasoningService(
                asset -> null, id -> java.util.Optional.empty(), java.time.Clock.systemUTC()) {
            @Override
            public com.rivell.workbench.intelligence.GenerativeReasoningService.GenerativeExecution generate(
                    com.rivell.workbench.intelligence.ReasoningSpec spec,
                    com.rivell.workbench.intelligence.SemanticState state) {
                throw new com.rivell.workbench.intelligence.ProviderOutOfCreditException(
                        "Provider account has no credits");
            }
        };
        var decisions = new com.rivell.workbench.intelligence.SemanticDecisionService(
                asset -> null, id -> java.util.Optional.empty(), java.time.Clock.systemUTC());
        var generator = new PositionLanguageGenerator(generation, decisions);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> generator.propose(
                new PositionLanguageGenerator.Draft("CUSTOMER", "MSA", "Term",
                        "Either party may terminate on notice.", "Termination on notice.")))
                .isInstanceOf(com.rivell.workbench.intelligence.ProviderOutOfCreditException.class);
    }

    @Test
    void theDiffNamesTheParagraphARewriteLeavesOut() {
        var original = "A. Either Party may terminate for material breach not remedied within 30 days.\n"
                + "B. Brackenhill may terminate this Agreement at any time on 90 days' written notice.";
        var rewrite = "(a) Either Party may terminate for material breach not remedied within 30 days.\n"
                + "(b) Cloudberry may terminate for non-payment only of undisputed amounts.";

        var diff = ClauseTextDiff.of(original, rewrite);

        assertThat(diff.removed()).containsExactly(
                "Brackenhill may terminate this Agreement at any time on 90 days' written notice.");
        assertThat(diff.added()).containsExactly(
                "Cloudberry may terminate for non-payment only of undisputed amounts.");
    }
}
