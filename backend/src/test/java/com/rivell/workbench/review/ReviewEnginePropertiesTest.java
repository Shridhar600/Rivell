package com.rivell.workbench.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ReviewEnginePropertiesTest {

    @Test
    void unsetPropertiesKeepEngineOneAndTheBandMeasuredInP0() {
        var properties = new ReviewEngineProperties(null, null);

        assertThat(properties.engine()).isEqualTo(ReviewEngine.V1);
        assertThat(properties.routing().adjudicateFrom()).isEqualTo(0.4);
        assertThat(properties.routing().adjudicateBelow()).isEqualTo(0.8);
        assertThat(properties.routing().maxAdjudications()).isEqualTo(40);
    }

    @Test
    void aBandThatIsEmptyOrOutsideZeroToOneOrANegativeBudgetIsRefusedAtStartup() {
        assertThatThrownBy(() -> new ReviewEngineProperties.Routing(0.8, 0.4, 40))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReviewEngineProperties.Routing(0.4, 1.2, 40))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReviewEngineProperties.Routing(0.4, 0.8, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
