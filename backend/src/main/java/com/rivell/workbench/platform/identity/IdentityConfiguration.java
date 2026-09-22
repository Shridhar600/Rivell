package com.rivell.workbench.platform.identity;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.rivell.workbench.organization.AuthorizationService;
import com.rivell.workbench.organization.IdentityProvider;

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
