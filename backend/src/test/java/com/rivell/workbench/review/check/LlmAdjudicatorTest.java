package com.rivell.workbench.review.check;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.rivell.workbench.review.AdjudicationSpecs;
import com.rivell.workbench.review.PolicyComparisonSpecs;

class LlmAdjudicatorTest {

    @Test
    void onlyJevsPlausibleOptionsAreOfferedInJevsOrderWithItsTopTwoAlways() {
        var probabilities = Map.of(
                PolicyComparisonSpecs.MEETS_PREFERRED, 0.45,
                PolicyComparisonSpecs.WITHIN_FALLBACK, 0.03,
                PolicyComparisonSpecs.BEYOND_FALLBACK, 0.40,
                PolicyComparisonSpecs.WALK_AWAY, 0.06,
                PolicyComparisonSpecs.UNCLEAR, 0.04,
                PolicyComparisonSpecs.NOT_ADDRESSED, 0.02);

        assertThat(LlmAdjudicator.plausibleOptions(probabilities)).containsExactly(
                PolicyComparisonSpecs.MEETS_PREFERRED, PolicyComparisonSpecs.BEYOND_FALLBACK,
                PolicyComparisonSpecs.WALK_AWAY);
    }

    @Test
    void aQuoteCountsOnlyWhenTheClauseHoldsIt() {
        var clause = "Supplier’s aggregate liability   shall not exceed the fees paid in the prior 3 months.";

        assertThat(LlmAdjudicator.quoted("supplier's aggregate liability shall not exceed", clause)).isTrue();
        assertThat(LlmAdjudicator.quoted("\"the fees paid in the prior 3 months.\"", clause)).isTrue();
        assertThat(LlmAdjudicator.quoted("liability is unlimited", clause)).isFalse();
        assertThat(LlmAdjudicator.quoted(" ", clause)).isFalse();
    }

    @Test
    void aQuoteTooShortToCarryAnAnswerDoesNotCount() {
        var clause = "The aggregate liability shall not exceed the fees paid.";

        assertThat(LlmAdjudicator.quoted("the", clause)).isFalse();
        assertThat(LlmAdjudicator.quoted("shall not", clause)).isFalse();
        assertThat(LlmAdjudicator.quoted("aggregate liability", clause)).isTrue();
    }

    @Test
    void theSecondOpinionMayOnlyAnswerWithAnOfferedOption() {
        var spec = AdjudicationSpecs.secondOpinion(Set.of(
                PolicyComparisonSpecs.MEETS_PREFERRED, PolicyComparisonSpecs.BEYOND_FALLBACK));

        assertThat(spec.allowedValues().get(AdjudicationSpecs.CATEGORY)).containsExactlyInAnyOrder(
                PolicyComparisonSpecs.MEETS_PREFERRED, PolicyComparisonSpecs.BEYOND_FALLBACK);
        assertThat(spec.fields()).containsOnlyKeys(
                AdjudicationSpecs.CATEGORY, AdjudicationSpecs.QUOTE, AdjudicationSpecs.REASON);
    }
}
