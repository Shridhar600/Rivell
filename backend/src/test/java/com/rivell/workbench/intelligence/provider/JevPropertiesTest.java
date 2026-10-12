package com.rivell.workbench.intelligence.provider;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JevPropertiesTest {

    @Test
    void anUnsetModelUsesThePinnedJevVersionNotTheDriftingAlias() {
        var properties = new JevProperties("https://api.typesafe.ai", "", " ", null, null);

        assertThat(properties.model()).isEqualTo("jev-1.13.0");
    }
}
