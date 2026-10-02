package com.rivell.workbench.platform.demo;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.rivell.workbench.intelligence.ModelBinding;
import com.rivell.workbench.intelligence.ModelBindingCatalog;
import com.rivell.workbench.intelligence.ModelBindings;
import com.rivell.workbench.intelligence.ModelPolicy;
import com.rivell.workbench.intelligence.ReasoningSpec;
import com.rivell.workbench.intelligence.SemanticAsset;

/**
 * Demo-only wiring. Nothing here is created unless {@code rivell.demo.seed-enabled=true},
 * so a normal startup is byte-for-byte unaffected and no scripted provider can be reached
 * outside an explicitly enabled local demo.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "rivell.demo", name = "seed-enabled", havingValue = "true")
public class DemoConfiguration {

    static final String PROVIDER_ID = "demo-scripted";
    static final String MODEL_ALIAS = "demo-model";

    @Bean
    @Primary
    DemoScriptedDecisionProvider demoScriptedDecisionProvider() {
        return new DemoScriptedDecisionProvider();
    }

    @Bean
    @Primary
    DemoScriptedGenerativeProvider demoScriptedGenerativeProvider() {
        return new DemoScriptedGenerativeProvider();
    }

    @Bean
    @Primary
    ModelBindingCatalog demoModelBindingCatalog() {
        return () -> new ModelBindings(
                new ModelBinding(PROVIDER_ID, MODEL_ALIAS),
                new ModelBinding(PROVIDER_ID, MODEL_ALIAS));
    }

    @Bean
    @Primary
    ModelPolicy demoModelPolicy() {
        return asset -> asset instanceof ReasoningSpec
                ? new ModelBinding(PROVIDER_ID, MODEL_ALIAS)
                : new ModelBinding(PROVIDER_ID, MODEL_ALIAS);
    }
}
