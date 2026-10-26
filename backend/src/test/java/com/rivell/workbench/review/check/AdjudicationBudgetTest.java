package com.rivell.workbench.review.check;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class AdjudicationBudgetTest {

    @Test
    void eachRunHasItsOwnBudgetAndTheCallOverItIsRefused() {
        var budget = new AdjudicationBudget(2, 16);
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();

        assertThat(budget.take(first)).isTrue();
        assertThat(budget.take(first)).isTrue();
        assertThat(budget.take(first)).isFalse();
        assertThat(budget.take(second)).isTrue();
    }

    @Test
    void aZeroBudgetSendsEveryDoubtfulPositionToTheUser() {
        assertThat(new AdjudicationBudget(0, 16).take(UUID.randomUUID())).isFalse();
    }

    @Test
    void onlyTheConfiguredNumberOfRunsIsRemembered() {
        var budget = new AdjudicationBudget(2, 2);
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        var third = UUID.randomUUID();

        assertThat(budget.take(first)).isTrue();
        assertThat(budget.take(first)).isTrue();
        assertThat(budget.take(second)).isTrue();
        assertThat(budget.take(third)).isTrue();

        assertThat(budget.take(first))
                .as("the evicted run's counter must start fresh")
                .isTrue();
    }
}
