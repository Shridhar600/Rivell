package com.rivell.workbench.intelligence.provider;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JevPropertiesTest {

    @Test
    void anUnsetModelUsesThePinnedJevVersionNotTheDriftingAlias() {
        var properties = new JevProperties("https://api.typesafe.ai", "", " ", null, null);

        assertThat(properties.model()).isEqualTo("jev-1.13.0");
    }

    @Test
    void theApiKeyIsRedactedFromTheRecordToString() {
        var properties = new JevProperties(
                "https://api.typesafe.ai", "jev-live-secret", null, null, null);

        assertThat(properties.toString())
                .doesNotContain("jev-live-secret")
                .contains("apiKey=<redacted>");
        assertThat(new JevProperties("https://api.typesafe.ai", " ", null, null, null).toString())
                .contains("apiKey=<absent>");
    }
}
