package com.rivell.workbench.taxonomy;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.rivell.workbench.intelligence.SemanticDecisionService;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TaxonomyProperties.class)
class TaxonomyConfiguration {

    /** Loads the seeded taxonomy once and fails startup if code names a topic it lacks. */
    @Bean
    ConceptRegistry conceptRegistry(ConceptRepository repository) {
        var registry = new ConceptRegistry(repository.version(), repository.findAll());
        CoreConcepts.ALL.forEach(registry::require);
        return registry;
    }

    @Bean
    TopicClassifier topicClassifier(
            ConceptRegistry registry, SemanticDecisionService decisions, TaxonomyProperties properties) {
        return new TopicClassifier(registry, decisions, properties);
    }
}
