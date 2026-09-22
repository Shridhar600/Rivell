package com.redstar.workbench.platform.identity;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.redstar.workbench.organization.AuthorizationService;
import com.redstar.workbench.organization.IdentityProvider;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DevIdentityProperties.class)
class IdentityConfiguration {

    @Bean
    IdentityProvider identityProvider(DevIdentityProperties properties) {
        return new DevIdentityProvider(properties);
    }

    @Bean
    AuthorizationService authorizationService() {
        return new SimpleAuthorizationService();
    }
}
