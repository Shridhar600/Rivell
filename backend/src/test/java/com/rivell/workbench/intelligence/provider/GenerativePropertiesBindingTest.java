package com.rivell.workbench.intelligence.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class GenerativePropertiesBindingTest {

    private static GenerativeProperties bind(Map<String, String> source) {
        return new Binder(new MapConfigurationPropertySource(source)).bind(
                "rivell.intelligence.generative",
                Bindable.of(GenerativeProperties.class)).get();
    }

    @Test
    void responseModeBindsFromConfiguration() {
        var properties = bind(Map.of(
                "rivell.intelligence.generative.api-key", "k",
                "rivell.intelligence.generative.response-mode", "json-object"));

        assertThat(properties.responseMode()).isEqualTo("json-object");
    }

    @Test
    void absentResponseModeDefaultsToStrictSchema() {
        var properties = bind(Map.of("rivell.intelligence.generative.api-key", "k"));

        assertThat(properties.responseMode()).isEqualTo("strict-schema");
    }

    @Test
    void unknownResponseModeFailsAtBinding() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> bind(Map.of(
                "rivell.intelligence.generative.api-key", "k",
                "rivell.intelligence.generative.response-mode", "yaml")))
                .hasRootCauseMessage("responseMode must be strict-schema or json-object");
    }
}
