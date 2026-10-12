package com.rivell.workbench.review.check;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class AdjudicationBudgetTest {

    @Test
    void eachRunHasItsOwnBudgetAndTheCallOverItIsRefused() {
        var budget = new AdjudicationBudget(2);
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();

        assertThat(budget.take(first)).isTrue();
        assertThat(budget.take(first)).isTrue();
        assertThat(budget.take(first)).isFalse();
        assertThat(budget.take(second)).isTrue();
    }

    @Test
    void aZeroBudgetSendsEveryDoubtfulPositionToTheUser() {
        assertThat(new AdjudicationBudget(0).take(UUID.randomUUID())).isFalse();
    }
}
