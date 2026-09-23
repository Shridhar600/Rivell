package com.rivell.workbench.intelligence;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rivell.workbench.intelligence.provider.IntelligenceProperties;
import com.rivell.workbench.intelligence.provider.JevProperties;
import com.rivell.workbench.intelligence.provider.JevProvider;

/**
 * Wiring of the intelligence module: centralized policy, provider registry, and the
 * decision service. The Jev adapter registers only when its API key is configured;
 * unconfigured environments fail closed at execution time.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({IntelligenceProperties.class, JevProperties.class})
class IntelligenceConfiguration {

    @Bean
    SemanticDecisionService semanticDecisionService(
            ModelPolicy modelPolicy, ProviderRegistry providerRegistry, Clock clock) {
        return new SemanticDecisionService(modelPolicy, providerRegistry, clock);
    }

    @Bean
    ModelPolicy propertiesModelPolicy(IntelligenceProperties properties, JevProperties jevProperties) {
        return spec -> new ModelBinding(properties.provider(), jevProperties.model());
    }

    @Bean
    ProviderRegistry providerRegistry(List<SemanticDecisionProvider> providers) {
        var byId = providers.stream()
                .collect(Collectors.toMap(SemanticDecisionProvider::id, Function.identity()));
        return providerId -> Optional.ofNullable(byId.get(providerId));
    }

    @Bean
    Clock decisionClock() {
        return Clock.systemUTC();
    }

    @Bean
    JevProvider jevProvider(JevProperties properties, ObjectMapper objectMapper) {
        return new JevProvider(properties, objectMapper);
    }
}
