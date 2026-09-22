package com.rivell.workbench.knowledge;

import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;

import com.rivell.workbench.foundation.DocumentStorage;
import com.rivell.workbench.organization.AuthorizationService;
import com.rivell.workbench.organization.IdentityProvider;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KnowledgeUploadProperties.class)
class KnowledgeServicesConfiguration {

    @Bean
    DuplicateConflictDetector duplicateConflictDetector() {
        return new ExactDuplicateConflictDetector();
    }

    @Bean
    KnowledgeIngestionService knowledgeIngestionService(
            DocumentStorage documentStorage,
            List<KnowledgeStructureExtractor> structureExtractors,
            KnowledgeRepository knowledgeRepository,
            DuplicateConflictDetector duplicateConflictDetector,
            TransactionTemplate transactionTemplate,
            IdentityProvider identityProvider,
            AuthorizationService authorizationService,
            KnowledgeUploadProperties uploadProperties) {
        return new KnowledgeIngestionService(
                documentStorage,
                structureExtractors,
                knowledgeRepository,
                duplicateConflictDetector,
                transactionTemplate,
                identityProvider,
                authorizationService,
                uploadProperties.maxFileSize());
    }

    @Bean
    KnowledgeQueryService knowledgeQueryService(
            KnowledgeRepository knowledgeRepository,
            IdentityProvider identityProvider,
            AuthorizationService authorizationService) {
        return new KnowledgeQueryService(
                knowledgeRepository, identityProvider, authorizationService);
    }

    @Bean
    CandidateReviewService candidateReviewService(
            KnowledgeRepository knowledgeRepository,
            IdentityProvider identityProvider,
            AuthorizationService authorizationService) {
        return new CandidateReviewService(knowledgeRepository, identityProvider, authorizationService);
    }

    @Bean
    KnowledgePublicationService knowledgePublicationService(
            KnowledgeRepository knowledgeRepository,
            IdentityProvider identityProvider,
            AuthorizationService authorizationService) {
        return new KnowledgePublicationService(knowledgeRepository, identityProvider, authorizationService);
    }
}
