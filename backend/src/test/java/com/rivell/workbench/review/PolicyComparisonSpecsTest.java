package com.rivell.workbench.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;

import org.junit.jupiter.api.Test;

class PolicyComparisonSpecsTest {

    @Test
    void aReversedQuestionOffersTheSameOptionsWithTheSameWordingInReverseOrder() {
        var asWritten = PolicyComparisonSpecs.positionQuestion("Compare.", false);
        var reversed = PolicyComparisonSpecs.positionQuestion("Compare.", true);

        var expectedOrder = new ArrayList<>(PolicyComparisonSpecs.OPTIONS.keySet());
        assertThat(asWritten.criteria().keySet()).containsExactlyElementsOf(expectedOrder);
        Collections.reverse(expectedOrder);
        assertThat(reversed.criteria().keySet()).containsExactlyElementsOf(expectedOrder);
        assertThat(reversed.criteria().keySet().iterator().next())
                .isEqualTo(PolicyComparisonSpecs.NOT_ADDRESSED);
        assertThat(reversed.criteria()).isEqualTo(PolicyComparisonSpecs.OPTIONS);
        assertThat(reversed.instructions()).isEqualTo(asWritten.instructions());
    }
}
