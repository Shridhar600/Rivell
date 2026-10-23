package com.rivell.workbench.review;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReviewEngineDefaultsTest {

    @Test
    void unsetPropertiesDefaultToOmissionsOffAndDefinitionsOff() {
        var properties = new ReviewEngineProperties(null, null, null, null, null);

        assertThat(properties.omissions()).isFalse();
        assertThat(properties.definitions()).isFalse();
    }
}
