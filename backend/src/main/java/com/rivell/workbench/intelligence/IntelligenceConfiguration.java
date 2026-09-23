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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rivell.workbench.intelligence.provider.IntelligenceProperties;
import com.rivell.workbench.intelligence.provider.JevProperties;
import com.rivell.workbench.intelligence.provider.JevProvider;

/**
 * Wiring of the intelligence module: centralized policy, provider registries, and the
 * decision/generative services. The Jev adapter registers only when its API key is
 * configured; the OpenAI-compatible generative adapter registers only when its key
 * is configured; unconfigured environments fail closed at execution time.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({IntelligenceProperties.class, JevProperties.class,
        com.rivell.workbench.intelligence.provider.GenerativeProperties.class})
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
    GenerativeProviderRegistry generativeProviderRegistry(
            List<com.rivell.workbench.intelligence.GenerativeReasoningProvider> providers) {
        var byId = providers.stream()
                .collect(Collectors.toMap(
                        com.rivell.workbench.intelligence.GenerativeReasoningProvider::id,
                        Function.identity()));
        return providerId -> Optional.ofNullable(byId.get(providerId));
    }

    @Bean
    GenerativeReasoningService generativeReasoningService(
            ModelPolicy modelPolicy, GenerativeProviderRegistry providerRegistry, Clock clock) {
        return new GenerativeReasoningService(modelPolicy, providerRegistry, clock);
    }

    @Bean
    Clock decisionClock() {
        return Clock.systemUTC();
    }

    @Bean
    JevProvider jevProvider(JevProperties properties, ObjectMapper objectMapper) {
        return new JevProvider(properties, objectMapper);
    }

    @Bean
    com.rivell.workbench.intelligence.GenerativeReasoningProvider openAiCompatibleGenerativeProvider(
            com.rivell.workbench.intelligence.provider.GenerativeProperties properties,
            ObjectMapper objectMapper) {
        var httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        var requestFactory = new org.springframework.http.client.JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        var restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                .build();
        return new com.rivell.workbench.intelligence.provider.OpenAiCompatibleGenerativeProvider(
                (path, payload) -> restClient.post()
                        .uri(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(payload)
                        .retrieve()
                        .body(String.class),
                objectMapper, properties);
    }
}
