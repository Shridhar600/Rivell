package com.rivell.workbench.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * DW-071 item 2: a flag threshold below the fixed 0.5 "addresses" line would let a position be
 * flagged and yet count as not addressing its clause, so one issue could yield both a deviation
 * finding and an omission. The configuration must refuse it at startup instead of silently
 * changing the meaning of a judgement.
 */
class PolicyComparisonPropertiesTest {

    private static PolicyComparisonProperties properties(double flagThreshold) {
        return new PolicyComparisonProperties(8, flagThreshold, 0.5,
                PolicyComparisonProperties.OrderProbe.OFF, 3,
                PolicyComparisonProperties.AdvisorySpots.ALL);
    }

    @Test
    void refusesAFlagThresholdBelowTheAddressesLineAtStartup() {
        assertThatThrownBy(() -> properties(0.4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0.5");
    }

    @Test
    void refusesNegativeAndNonFiniteThresholds() {
        assertThatThrownBy(() -> properties(-0.1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not negative");
        assertThatThrownBy(() -> properties(Double.NaN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finite");
    }

    @Test
    void acceptsTheAddressesLineAndAbove() {
        assertThatCode(() -> properties(0.5)).doesNotThrowAnyException();
        assertThat(properties(0.5).flagThreshold()).isEqualTo(0.5);
        assertThat(properties(0.8).flagThreshold()).isEqualTo(0.8);
    }

    @Test
    void leavesAnUnsetThresholdAtTheDefault() {
        assertThat(properties(0.0).flagThreshold()).isEqualTo(0.6);
    }
}
