package com.rivell.workbench.platform.identity;

import org.slf4j.LoggerFactory;
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
        LoggerFactory.getLogger(IdentityConfiguration.class).warn(
                "No sign-in: every request acts as development user {} in organization {} with roles {}",
                properties.userId(), properties.organizationId(), properties.roles());
        return new DevIdentityProvider(properties);
    }

    @Bean
    AuthorizationService authorizationService() {
        return new SimpleAuthorizationService();
    }
}
