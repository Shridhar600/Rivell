package com.rivell.workbench.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.rivell.workbench.intelligence.YesNoQuestion;

class WalkAwayAbsenceSpecsTest {

    @Test
    void aWalkAwayListingSeveralCasesIsSplitIntoOneQuestionPerCase() {
        assertThat(WalkAwayAbsenceSpecs.cases("No termination for convenience, or a termination "
                + "payment that compensates the supplier's lost profit."))
                .containsExactly("No termination for convenience",
                        "a termination payment that compensates the supplier's lost profit");
        assertThat(WalkAwayAbsenceSpecs.cases("No termination right for convenience at all. "
                + "Also any charge that applies where we terminate for vendor breach."))
                .containsExactly("No termination right for convenience at all",
                        "any charge that applies where we terminate for vendor breach");
        assertThat(WalkAwayAbsenceSpecs.cases("Uncapped liability; or a cap below fees paid"))
                .containsExactly("Uncapped liability", "a cap below fees paid");
    }

    @Test
    void anOrInsideOneCaseDoesNotSplitIt() {
        assertThat(WalkAwayAbsenceSpecs.cases("No liability for loss of profit or revenue."))
                .containsExactly("No liability for loss of profit or revenue");
    }

    @Test
    void eachCaseIsAskedOnItsOwnSoAMixedWalkAwayIsNotOneHedgedAnswer() {
        var spec = WalkAwayAbsenceSpecs.spec(List.of(
                List.of("No termination for convenience", "a termination payment"),
                List.of("Uncapped liability")));

        var questions = spec.pack().questions();
        assertThat(questions.keySet()).containsExactly(
                WalkAwayAbsenceSpecs.key(0, 0), WalkAwayAbsenceSpecs.key(0, 1),
                WalkAwayAbsenceSpecs.key(1, 0));
        assertThat(((YesNoQuestion) questions.get(WalkAwayAbsenceSpecs.key(0, 1))).instructions())
                .endsWith("The walk-away: a termination payment");
    }
}
