package com.redstar.workbench.contracts;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;

import com.redstar.workbench.foundation.DocumentStorage;
import com.redstar.workbench.organization.AuthorizationService;
import com.redstar.workbench.organization.IdentityProvider;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(UploadProperties.class)
class ContractServicesConfiguration {

    @Bean
    ContractIngestionService contractIngestionService(
            DocumentStorage documentStorage,
            DocumentStructureParser documentStructureParser,
            ContractRepository contractRepository,
            TransactionTemplate transactionTemplate,
            IdentityProvider identityProvider,
            AuthorizationService authorizationService,
            UploadProperties uploadProperties) {
        return new ContractIngestionService(
                documentStorage,
                documentStructureParser,
                contractRepository,
                transactionTemplate,
                identityProvider,
                authorizationService,
                uploadProperties.maxFileSize());
    }

    @Bean
    ContractQueryService contractQueryService(
            ContractRepository contractRepository,
            IdentityProvider identityProvider,
            AuthorizationService authorizationService) {
        return new ContractQueryService(
                contractRepository, identityProvider, authorizationService);
    }

    @Bean
    ContractDownloadService contractDownloadService(
            ContractQueryService contractQueryService, DocumentStorage documentStorage) {
        return new ContractDownloadService(contractQueryService, documentStorage);
    }
}
