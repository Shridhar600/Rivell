package com.rivell.workbench.review;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReviewEngineDefaultsTest {

    @Test
    void unsetPropertiesDefaultToEngineV2OmissionsOffAndDefinitionsOn() {
        var properties = new ReviewEngineProperties(null, null, null, null);

        assertThat(properties.engine()).isEqualTo(ReviewEngine.V2);
        assertThat(properties.omissions()).isFalse();
        assertThat(properties.definitions()).isTrue();
    }
}
