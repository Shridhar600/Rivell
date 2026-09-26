package com.rivell.workbench.redline;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.rivell.workbench.contracts.ContractQueryService;
import com.rivell.workbench.foundation.DocumentStorage;
import com.rivell.workbench.organization.AuthorizationService;
import com.rivell.workbench.organization.IdentityProvider;
import com.rivell.workbench.redline.api.RedlineApiService;

@Configuration(proxyBeanMethods = false)
class RedlineConfiguration {

    @Bean
    RedlinePlanResolver redlinePlanResolver() {
        return new RedlinePlanResolver();
    }

    @Bean
    RedlineExecutionService redlineExecutionService(
            IdentityProvider identityProvider,
            AuthorizationService authorizationService,
            ApprovedChangeSetRepository changeSetRepository,
            RedlineExecutionRepository executionRepository,
            ContractQueryService contractQueryService,
            DocumentStorage documentStorage,
            RedlineRenderer redlineRenderer,
            RedlineVerifier redlineVerifier,
            RedlinePlanResolver redlinePlanResolver,
            Clock clock) {
        return new RedlineExecutionService(identityProvider, authorizationService,
                changeSetRepository, executionRepository, contractQueryService,
                documentStorage, redlineRenderer, redlineVerifier, redlinePlanResolver,
                clock);
    }

    @Bean
    RedlineApiService redlineApiService(RedlineExecutionService redlineExecutionService) {
        return new RedlineApiService(redlineExecutionService);
    }
}
