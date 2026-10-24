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

    @Test
    void reasoningIsSentAsNoneWhenDisabled() {
        var properties = bind(Map.of(
                "rivell.intelligence.generative.api-key", "k",
                "rivell.intelligence.generative.reasoning.enabled", "false",
                "rivell.intelligence.generative.reasoning.effort", "high"));

        assertThat(properties.reasoning().wireEffort()).isEqualTo("none");
    }

    @Test
    void enabledReasoningSendsTheConfiguredEffort() {
        var properties = bind(Map.of(
                "rivell.intelligence.generative.api-key", "k",
                "rivell.intelligence.generative.reasoning.enabled", "true",
                "rivell.intelligence.generative.reasoning.effort", "low"));

        assertThat(properties.reasoning().wireEffort()).isEqualTo("low");
    }

    @Test
    void enabledReasoningWithoutAnEffortLeavesTheProviderDefault() {
        var properties = bind(Map.of(
                "rivell.intelligence.generative.api-key", "k",
                "rivell.intelligence.generative.reasoning.enabled", "true",
                "rivell.intelligence.generative.reasoning.effort", ""));

        assertThat(properties.reasoning().wireEffort()).isNull();
    }

    @Test
    void unsetReasoningSendsNothingForModelsWithoutTheField() {
        var properties = bind(Map.of(
                "rivell.intelligence.generative.api-key", "k",
                "rivell.intelligence.generative.reasoning.enabled", ""));

        assertThat(properties.reasoning().wireEffort()).isNull();
    }

    @Test
    void enabledReasoningWithEffortNoneFailsAtBinding() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> bind(Map.of(
                "rivell.intelligence.generative.api-key", "k",
                "rivell.intelligence.generative.reasoning.enabled", "true",
                "rivell.intelligence.generative.reasoning.effort", "none")))
                .hasRootCauseMessage("reasoning.effort none contradicts reasoning.enabled=true");
    }
}
