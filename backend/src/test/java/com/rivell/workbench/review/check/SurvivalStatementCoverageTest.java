package com.rivell.workbench.review.check;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.rivell.workbench.contracts.ClauseSemantics;
import com.rivell.workbench.contracts.ConceptClassification;
import com.rivell.workbench.contracts.ContractRegionSegmenter;
import com.rivell.workbench.contracts.FactCorrection;
import com.rivell.workbench.taxonomy.ConceptKey;
import com.rivell.workbench.taxonomy.CoreConcepts;

/** DW-100: deterministic survival statements on the contract cover named confidentiality clauses. */
class SurvivalStatementCoverageTest {

    private static final UUID VERSION = UUID.randomUUID();

    @Test
    void sectionNumberStatementCoversThatConfidentialityClauseOnly() {
        var clause2 = confidentialityClause("2. Confidentiality",
                "2.1 Each Party shall protect the other Party's confidential information.");
        var clause4 = confidentialityClause("4. Confidentiality of Deliverables",
                "4.1 Supplier shall protect the confidentiality of Customer deliverables.");
        var survival = clause("5. Survival",
                "The obligations in Section 2 survive termination of this Agreement for three (3) years.");

        var covered = coverage(List.of(clause2, clause4, survival), List.of(clause2, clause4));

        assertThat(covered.coveredClauseIds()).containsExactly(clause2.id());
        assertThat(covered.sourceClauseIds()).containsExactly(survival.id());
    }

    @Test
    void namingConfidentialityCoversEveryConfidentialityClause() {
        var clause2 = confidentialityClause("2. Confidentiality",
                "2.1 Each Party shall protect the other Party's confidential information.");
        var clause4 = confidentialityClause("4. Confidentiality of Deliverables",
                "4.1 Supplier shall protect the confidentiality of Customer deliverables.");
        var survival = clause("5. Term and Survival",
                "The obligations of confidentiality survive termination of this Agreement for five (5) years.");

        var covered = coverage(List.of(clause2, clause4, survival), List.of(clause2, clause4));

        assertThat(covered.coveredClauseIds()).containsExactlyInAnyOrder(clause2.id(), clause4.id());
    }

    @Test
    void romanClauseRangeCoversEachClauseInTheRange() {
        var clause11 = confidentialityClause("XI CONFIDENTIALITY",
                "The Receiving Party shall protect the Confidential Information.");
        var clause12 = confidentialityClause("XII CONFIDENTIALITY OF DELIVERABLES",
                "The Supplier shall protect the confidentiality of deliverables.");
        var survival = clause("XIII TERM AND SURVIVAL", "Clauses XI to XII survive termination.");

        var covered = coverage(List.of(clause11, clause12, survival), List.of(clause11, clause12));

        assertThat(covered.coveredClauseIds())
                .containsExactlyInAnyOrder(clause11.id(), clause12.id());
    }

    @Test
    void obligationsInThisAgreementCoversEveryConfidentialityClause() {
        var clause2 = confidentialityClause("2. Confidentiality",
                "2.1 Each Party shall protect confidential information.");
        var clause4 = confidentialityClause("4. Confidentiality of Deliverables",
                "4.1 Supplier shall protect the confidentiality of deliverables.");
        var survival = clause("5. Survival",
                "The obligations in this Agreement survive termination of this Agreement.");

        var covered = coverage(List.of(clause2, clause4, survival), List.of(clause2, clause4));

        assertThat(covered.coveredClauseIds()).containsExactlyInAnyOrder(clause2.id(), clause4.id());
    }

    @Test
    void survivingEntityAndBarePartiesSurviveAreNotSurvivalStatements() {
        var clause2 = confidentialityClause("2. Confidentiality",
                "2.1 Each Party shall protect confidential information.");
        var assignment = clause("9. Assignment",
                "The surviving entity may assign this Agreement without consent. The parties survive.");
        var termination = clause("8. Termination",
                "Either Party may terminate for convenience on sixty (60) days' notice.");

        var covered = coverage(List.of(clause2, assignment, termination), List.of(clause2));

        assertThat(covered.coveredClauseIds()).isEmpty();
    }

    @Test
    void negatedSurvivalIsNotCoverage() {
        var clause2 = confidentialityClause("2. Confidentiality",
                "2.1 Each Party shall protect confidential information.");
        var survival = clause("5. Survival",
                "The obligations of confidentiality shall not survive termination of this Agreement.");

        var covered = coverage(List.of(clause2, survival), List.of(clause2));

        assertThat(covered.coveredClauseIds()).isEmpty();
    }

    @Test
    void conditionalOrPossibleSurvivalIsNotCoverage() {
        var clause2 = confidentialityClause("2. Confidentiality",
                "2.1 Each Party shall protect confidential information.");
        var maySurvive = clause("5. Survival",
                "The obligations of confidentiality may survive termination of this Agreement.");
        var onlyIf = clause("6. Survival",
                "The obligations of confidentiality survive only if the Receiving Party asks.");

        assertThat(coverage(List.of(clause2, maySurvive), List.of(clause2)).coveredClauseIds())
                .isEmpty();
        assertThat(coverage(List.of(clause2, onlyIf), List.of(clause2)).coveredClauseIds())
                .isEmpty();
    }

    @Test
    void survivalOfAnUnrelatedObligationCoversNoConfidentialityClause() {
        var clause2 = confidentialityClause("2. Confidentiality",
                "2.1 Each Party shall protect confidential information.");
        var survival = clause("6. Survival",
                "The indemnification obligations survive termination for two (2) years.");

        var covered = coverage(List.of(clause2, survival), List.of(clause2));

        assertThat(covered.coveredClauseIds()).isEmpty();
    }

    @Test
    void survivalOnAssignmentAloneIsNotTerminationCoverage() {
        var clause2 = confidentialityClause("2. Confidentiality",
                "2.1 Each Party shall protect confidential information.");
        var assignment = clause("9. Assignment",
                "The confidentiality obligations survive assignment of this Agreement.");

        var covered = coverage(List.of(clause2, assignment), List.of(clause2));

        assertThat(covered.coveredClauseIds()).isEmpty();
    }

    @Test
    void decimalSectionReferenceCoversOnlyThatSubClause() {
        var clause21 = confidentialityClause("2.1 Confidentiality",
                "The Receiving Party shall protect confidential information.");
        var clause22 = confidentialityClause("2.2 Confidentiality of Deliverables",
                "The Supplier shall protect the confidentiality of deliverables.");
        var survival = clause("5. Survival",
                "The obligations in Section 2.1 survive termination of this Agreement.");

        var covered = coverage(List.of(clause21, clause22, survival), List.of(clause21, clause22));

        assertThat(covered.coveredClauseIds()).containsExactly(clause21.id());
    }

    @Test
    void parentheticalSectionListCoversEveryNamedClause() {
        var clause2 = confidentialityClause("2. Confidentiality",
                "2.1 Each Party shall protect confidential information.");
        var clause4 = confidentialityClause("4. Confidentiality of Deliverables",
                "4.1 Supplier shall protect the confidentiality of deliverables.");
        var survival = clause("5. Survival",
                "Sections 2 (Confidentiality) and 4 (Confidentiality of Deliverables) "
                        + "survive termination.");

        var covered = coverage(List.of(clause2, clause4, survival), List.of(clause2, clause4));

        assertThat(covered.coveredClauseIds()).containsExactlyInAnyOrder(clause2.id(), clause4.id());
    }

    @Test
    void unnumberedHeadingsFallBackToTheirClausePosition() {
        var clause2 = confidentialityClause("Confidentiality",
                "2.1 Each Party shall protect confidential information.");
        var clause4 = confidentialityClause("Confidentiality of Deliverables",
                "4.1 Supplier shall protect the confidentiality of deliverables.");
        var survival = clause("Term and Survival", "Clauses 1 and 2 survive termination.");

        var covered = coverage(List.of(clause2, clause4, survival), List.of(clause2, clause4));

        assertThat(covered.coveredClauseIds()).containsExactlyInAnyOrder(clause2.id(), clause4.id());
    }

    @Test
    void confidentialityWordingStillCoversWhenTheSectionReferenceIsStale() {
        var clause2 = confidentialityClause("2. Confidentiality",
                "2.1 Each Party shall protect confidential information.");
        var survival = clause("5. Survival",
                "The confidentiality obligations survive as set out in Section 9.");

        var covered = coverage(List.of(clause2, survival), List.of(clause2));

        assertThat(covered.coveredClauseIds()).containsExactly(clause2.id());
    }

    @Test
    void headingNamingConfidentialityGivesContextToTheBodyStatement() {
        var clause2 = confidentialityClause("2. Confidentiality",
                "2.1 Each Party shall protect confidential information.");
        var survival = clause("5. Survival of Confidentiality",
                "The obligations survive termination of this Agreement.");

        var covered = coverage(List.of(clause2, survival), List.of(clause2));

        assertThat(covered.coveredClauseIds()).containsExactly(clause2.id());
    }

    @Test
    void headingContextDoesNotTurnPartiesSurviveIntoCoverage() {
        var clause2 = confidentialityClause("2. Confidentiality",
                "2.1 Each Party shall protect confidential information.");
        var survival = clause("5. Survival of Confidentiality", "The parties survive.");
        var agreement = clause("6. Survival of Confidentiality", "This Agreement survives.");

        assertThat(coverage(List.of(clause2, survival), List.of(clause2)).coveredClauseIds())
                .isEmpty();
        assertThat(coverage(List.of(clause2, agreement), List.of(clause2)).coveredClauseIds())
                .isEmpty();
    }

    @Test
    void hugeRangeIsHandledWithoutFailingTheCheck() {
        var clause2 = confidentialityClause("2. Confidentiality",
                "2.1 Each Party shall protect confidential information.");
        var survival = clause("5. Survival",
                "Clauses 1 to 2147483647 survive termination.");

        var covered = coverage(List.of(clause2, survival), List.of(clause2));

        assertThat(covered.coveredClauseIds()).containsExactly(clause2.id());
    }

    @Test
    void terminationDoesNotAffectConfidentialInformationObligations() {
        var clause2 = confidentialityClause("2. Confidentiality",
                "2.1 Each Party shall protect the other Party's confidential information.");
        var survival = clause("3. Term and Survival",
                "Termination does not affect obligations in respect of Confidential "
                        + "Information disclosed before termination.");
        var obligationsOf = clause("4. Term and Survival",
                "Termination shall not affect the obligations of confidentiality.");

        var covered = coverage(List.of(clause2, survival), List.of(clause2));

        assertThat(covered.coveredClauseIds()).containsExactly(clause2.id());
        assertThat(covered.sourceClauseIds()).containsExactly(survival.id());
        assertThat(coverage(List.of(clause2, obligationsOf), List.of(clause2))
                .coveredClauseIds()).containsExactly(clause2.id());
    }

    @Test
    void expiryWithoutPrejudiceToNamedConfidentialityClauseCoversIt() {
        var clause2 = confidentialityClause("2. Confidentiality",
                "2.1 Each Party shall protect the other Party's confidential information.");
        var clause8 = confidentialityClause("8. Confidentiality of Deliverables",
                "8.1 Supplier shall protect the confidentiality of Customer deliverables.");
        var survival = clause("9. Expiry",
                "Expiry shall not affect the obligations under Section 2.");
        var prejudice = clause("10. Expiry",
                "Expiry is without prejudice to the obligations under Section 2.");

        var covered = coverage(List.of(clause2, clause8, survival),
                List.of(clause2, clause8));

        assertThat(covered.coveredClauseIds()).containsExactly(clause2.id());
        assertThat(covered.sourceClauseIds()).containsExactly(survival.id());
        assertThat(coverage(List.of(clause2, clause8, prejudice),
                List.of(clause2, clause8)).coveredClauseIds()).containsExactly(clause2.id());
    }

    @Test
    void continuingConfidentialityObligationsForAStatedPeriodCoverTheClause() {
        var clause2 = confidentialityClause("2. Confidentiality",
                "2.1 Each Party shall protect the other Party's confidential information.");
        var survival = clause("3. Term and Survival",
                "The obligations of confidentiality and restricted use continue for "
                        + "three (3) years from the date of each disclosure, and "
                        + "indefinitely in respect of trade secrets.");

        var covered = coverage(List.of(clause2, survival), List.of(clause2));

        assertThat(covered.coveredClauseIds()).containsExactly(clause2.id());
        assertThat(covered.sourceClauseIds()).containsExactly(survival.id());
    }

    @Test
    void boundedContinuationThatStartsWithOnlyForStillCovers() {
        var clause2 = confidentialityClause("2. Confidentiality",
                "2.1 Each Party shall protect the other Party's confidential information.");
        var survival = clause("3. Survival",
                "The obligations of confidentiality continue only for three (3) years "
                        + "after termination.");

        var covered = coverage(List.of(clause2, survival), List.of(clause2));

        assertThat(covered.coveredClauseIds()).containsExactly(clause2.id());
    }

    @Test
    void remainingInForceOrApplyingAfterTerminationCoversTheClause() {
        var clause2 = confidentialityClause("2. Confidentiality",
                "2.1 Each Party shall protect the other Party's confidential information.");
        var remains = clause("3. Survival",
                "The obligations of confidentiality remain in force indefinitely.");
        var applies = clause("4. Survival",
                "The confidentiality obligations apply after termination of this Agreement.");

        assertThat(coverage(List.of(clause2, remains), List.of(clause2)).coveredClauseIds())
                .containsExactly(clause2.id());
        assertThat(coverage(List.of(clause2, applies), List.of(clause2)).coveredClauseIds())
                .containsExactly(clause2.id());
    }

    @Test
    void noAffectAndContinuationNearMissesAreNotCoverage() {
        var clause2 = confidentialityClause("2. Confidentiality",
                "2.1 Each Party shall protect the other Party's confidential information.");
        var fees = clause("3. Termination",
                "Termination does not affect the fees payable under this Agreement.");
        var indemnity = clause("4. Termination",
                "Termination does not affect obligations in respect of indemnification.");
        var notContinue = clause("5. Survival",
                "The obligations of confidentiality do not continue after termination.");
        var mayContinue = clause("6. Survival",
                "The obligations of confidentiality may continue after termination.");
        var conditional = clause("7. Survival",
                "The obligations of confidentiality continue only if the Disclosing "
                        + "Party asks.");
        var notRequired = clause("8. Survival",
                "The obligations of confidentiality are not required to continue "
                        + "after termination.");
        var noObligation = clause("9. Survival",
                "The obligations of confidentiality are under no obligation to "
                        + "continue after termination.");
        var noPeriod = clause("10. Survival",
                "The obligations of confidentiality continue to apply to the Services.");

        assertThat(coverage(List.of(clause2, fees), List.of(clause2)).coveredClauseIds())
                .isEmpty();
        assertThat(coverage(List.of(clause2, indemnity), List.of(clause2)).coveredClauseIds())
                .isEmpty();
        assertThat(coverage(List.of(clause2, notContinue), List.of(clause2)).coveredClauseIds())
                .isEmpty();
        assertThat(coverage(List.of(clause2, mayContinue), List.of(clause2)).coveredClauseIds())
                .isEmpty();
        assertThat(coverage(List.of(clause2, conditional), List.of(clause2)).coveredClauseIds())
                .isEmpty();
        assertThat(coverage(List.of(clause2, notRequired), List.of(clause2)).coveredClauseIds())
                .isEmpty();
        assertThat(coverage(List.of(clause2, noObligation), List.of(clause2)).coveredClauseIds())
                .isEmpty();
        assertThat(coverage(List.of(clause2, noPeriod), List.of(clause2)).coveredClauseIds())
                .isEmpty();
    }

    @Test
    void noStatementCoversNothing() {
        var clause2 = confidentialityClause("2. Confidentiality",
                "2.1 Each Party shall protect confidential information.");
        var termination = clause("3. Termination",
                "Either Party may terminate for convenience on sixty (60) days' notice.");

        var covered = coverage(List.of(clause2, termination), List.of(clause2));

        assertThat(covered.coveredClauseIds()).isEmpty();
    }

    private static SurvivalStatementCoverage.Coverage coverage(
            List<ClauseSemantics> clauses, List<ClauseSemantics> candidates) {
        return SurvivalStatementCoverage.coverage(clauses, candidates);
    }

    private static ClauseSemantics confidentialityClause(String heading, String body) {
        return clause(heading, body, CoreConcepts.CONFIDENTIALITY_OBLIGATIONS);
    }

    private static ClauseSemantics clause(String heading, String body) {
        return clause(heading, body, null);
    }

    private static ClauseSemantics clause(String heading, String body, ConceptKey primary) {
        var classification = primary == null
                ? new ConceptClassification(List.of(), List.of(), Map.of())
                : new ConceptClassification(List.of(primary), List.of(), Map.of(primary, 0.95));
        return new ClauseSemantics(UUID.randomUUID(), VERSION, UUID.randomUUID(),
                ContractRegionSegmenter.SemanticRegionKind.CLAUSE, List.of(),
                heading + "\n" + body, classification, List.of(), List.of(),
                FactCorrection.NONE, null);
    }
}
