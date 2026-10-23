package com.rivell.workbench.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ReviewEnginePropertiesTest {

    @Test
    void unsetPropertiesDefaultToTheBandMeasuredInP0() {
        var properties = new ReviewEngineProperties(null, null, null, null, null);

        assertThat(properties.routing().adjudicateFrom()).isEqualTo(0.4);
        assertThat(properties.routing().adjudicateBelow()).isEqualTo(0.8);
        assertThat(properties.routing().maxAdjudications()).isEqualTo(40);
    }

    @Test
    void aBandThatIsEmptyOrOutsideZeroToOneOrANegativeBudgetIsRefusedAtStartup() {
        assertThatThrownBy(() -> new ReviewEngineProperties.Routing(0.8, 0.4, 40, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReviewEngineProperties.Routing(0.4, 1.2, 40, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReviewEngineProperties.Routing(0.4, 0.8, -1, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theSeverityR3LeversDefaultOnWithTheMeasuredMargin() {
        var properties = new ReviewEngineProperties(null, null, null, null, null);

        assertThat(properties.severity().agreedWorseCategory()).isTrue();
        assertThat(properties.routing().withinSplitMargin()).isEqualTo(0.45);
        assertThat(properties.fallbackConditions().enabled()).isTrue();
        assertThat(properties.fallbackConditions().maxChecks()).isEqualTo(40);
    }

    @Test
    void aNegativeWithinSplitMarginTurnsRoutingByTheSplitOff() {
        var routing = new ReviewEngineProperties.Routing(0.4, 0.8, 40, -1.0);

        assertThat(routing.withinSplitMargin()).isNegative();
        assertThat(routing.withinSplitEnabled()).isFalse();
        assertThat(new ReviewEngineProperties.Routing(0.4, 0.8, 40, 0.45).withinSplitEnabled()).isTrue();
    }

    @Test
    void aMarginAboveOneOrANegativeCheckBudgetIsRefusedAtStartup() {
        assertThatThrownBy(() -> new ReviewEngineProperties.Routing(0.4, 0.8, 40, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReviewEngineProperties.FallbackConditions(true, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
