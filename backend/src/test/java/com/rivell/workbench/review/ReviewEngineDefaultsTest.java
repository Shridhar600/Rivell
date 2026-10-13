package com.rivell.workbench.review;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReviewEngineDefaultsTest {

    @Test
    void unsetPropertiesDefaultToEngineV2WithTheOmissionsLaneOff() {
        var properties = new ReviewEngineProperties(null, null, null);

        assertThat(properties.engine()).isEqualTo(ReviewEngine.V2);
        assertThat(properties.omissions()).isFalse();
    }
}
