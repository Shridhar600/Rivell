package com.rivell.workbench.contracts;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;

import com.rivell.workbench.foundation.DocumentStorage;
import com.rivell.workbench.intelligence.GenerativeReasoningService;
import com.rivell.workbench.intelligence.SemanticDecisionService;
import com.rivell.workbench.organization.AuthorizationService;
import com.rivell.workbench.organization.IdentityProvider;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({UploadProperties.class, ContractEnrichmentProperties.class})
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
            ContractSemanticsRepository semanticsRepository,
            IdentityProvider identityProvider,
            AuthorizationService authorizationService) {
        return new ContractQueryService(
                contractRepository, semanticsRepository, identityProvider,
                authorizationService);
    }

    @Bean
    ContractDownloadService contractDownloadService(
            ContractQueryService contractQueryService, DocumentStorage documentStorage) {
        return new ContractDownloadService(contractQueryService, documentStorage);
    }

    @Bean
    ContractFactExtractor contractFactExtractor(
            SemanticDecisionService decisionService,
            GenerativeReasoningService reasoningService,
            ContractEnrichmentProperties properties) {
        return new ContractFactExtractor(decisionService, reasoningService, properties);
    }

    @Bean
    ContractSemanticEnricher contractSemanticEnricher(
            SemanticDecisionService decisionService,
            ContractFactExtractor factExtractor,
            ContractEnrichmentProperties properties) {
        return new ContractSemanticEnricher(decisionService, factExtractor, properties);
    }

    @Bean
    ContractEnrichmentService contractEnrichmentService(
            ContractQueryService queryService,
            ContractRepository contractRepository,
            ContractSemanticsRepository semanticsRepository,
            ContractSemanticEnricher enricher,
            TransactionTemplate transactionTemplate,
            IdentityProvider identityProvider,
            AuthorizationService authorizationService) {
        return new ContractEnrichmentService(
                queryService, contractRepository, semanticsRepository, enricher,
                transactionTemplate, identityProvider, authorizationService);
    }
}
