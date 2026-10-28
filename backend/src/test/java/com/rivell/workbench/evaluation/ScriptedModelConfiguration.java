package com.rivell.workbench.evaluation;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.rivell.workbench.intelligence.GenerativeReasoningProvider;
import com.rivell.workbench.intelligence.ModelBinding;
import com.rivell.workbench.intelligence.ModelBindingCatalog;
import com.rivell.workbench.intelligence.ModelBindings;
import com.rivell.workbench.intelligence.ModelPolicy;
import com.rivell.workbench.intelligence.ReasoningSpec;
import com.rivell.workbench.intelligence.SemanticDecisionProvider;

@TestConfiguration(proxyBeanMethods = false)
public class ScriptedModelConfiguration {

    @Bean
    @Primary
    ModelBindingCatalog scriptedModelBindingCatalog() {
        return () -> new ModelBindings(
                new ModelBinding("scripted-test", "scripted-test-1"),
                new ModelBinding("scripted-test", "scripted-test-1"));
    }

    @Bean
    @Primary
    ModelPolicy scriptedModelPolicy(ModelBindingCatalog catalog) {
        return asset -> asset instanceof ReasoningSpec
                ? catalog.current().generativeProvider()
                : catalog.current().decisionProvider();
    }

    @Bean
    @Primary
    SemanticDecisionProvider scriptedDecisionProvider() {
        return new ScriptedDecisionProvider();
    }

    @Bean
    @Primary
    GenerativeReasoningProvider scriptedGenerativeProvider() {
        return new ScriptedGenerativeProvider();
    }
}
