package com.rivell.workbench.review.check;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.rivell.workbench.review.PolicyComparisonSpecs;

class PolicyComparisonOutcomeTest {

    private static final String WALK = PolicyComparisonSpecs.WALK_AWAY;
    private static final String BEYOND = PolicyComparisonSpecs.BEYOND_FALLBACK;
    private static final String UNCLEAR = PolicyComparisonSpecs.UNCLEAR;
    private static final String WITHIN = PolicyComparisonSpecs.WITHIN_FALLBACK;

    @Test
    void p1AWorseSecondOpinionCategoryWins() {
        assertThat(PolicyComparisonOutcome.worse(WITHIN, BEYOND)).isEqualTo(BEYOND);
        assertThat(PolicyComparisonOutcome.worse(WITHIN, WALK)).isEqualTo(WALK);
        assertThat(PolicyComparisonOutcome.worse(BEYOND, WALK)).isEqualTo(WALK);
        assertThat(PolicyComparisonOutcome.worse(WITHIN, UNCLEAR)).isEqualTo(UNCLEAR);
    }

    @Test
    void p1ABetterSecondOpinionCategoryLeavesJevsCategory() {
        assertThat(PolicyComparisonOutcome.worse(WALK, WITHIN)).isEqualTo(WALK);
        assertThat(PolicyComparisonOutcome.worse(BEYOND, WITHIN)).isEqualTo(BEYOND);
        assertThat(PolicyComparisonOutcome.worse(WALK, BEYOND)).isEqualTo(WALK);
    }

    @Test
    void p1CategoriesOfTheSameSeverityKeepJevsCategory() {
        // BEYOND_FALLBACK and UNCLEAR both map to MEDIUM: swapping them would change the finding's
        // family and action but never its severity, so only a strictly worse severity tier wins
        assertThat(PolicyComparisonOutcome.worse(UNCLEAR, BEYOND)).isEqualTo(UNCLEAR);
        assertThat(PolicyComparisonOutcome.worse(BEYOND, UNCLEAR)).isEqualTo(BEYOND);
        assertThat(PolicyComparisonOutcome.worse(WITHIN, WITHIN)).isEqualTo(WITHIN);
    }

    @Test
    void p1ASecondOpinionOutsideTheFlaggingCategoriesChangesNothing() {
        assertThat(PolicyComparisonOutcome.worse(WITHIN, PolicyComparisonSpecs.MEETS_PREFERRED)).isEqualTo(WITHIN);
        assertThat(PolicyComparisonOutcome.worse(BEYOND, null)).isEqualTo(BEYOND);
    }
}
