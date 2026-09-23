package com.rivell.workbench.knowledge;

import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;

import com.rivell.workbench.foundation.DocumentStorage;
import com.rivell.workbench.intelligence.DecisionSpec;
import com.rivell.workbench.intelligence.SemanticDecisionService;
import com.rivell.workbench.organization.AuthorizationService;
import com.rivell.workbench.organization.IdentityProvider;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
        KnowledgeUploadProperties.class, ConceptClassificationProperties.class,
        SemanticPolicyProperties.class})
class KnowledgeServicesConfiguration {

    @Bean
    DecisionSpec extractionUnitConceptSpec() {
        return ExtractionUnitConceptSpec.spec();
    }

    @Bean
    SemanticCompilationService semanticCompilationService(
            com.rivell.workbench.intelligence.SemanticDecisionService decisionService,
            SemanticPolicyProperties policyProperties,
            ConceptClassificationProperties conceptProperties,
            DuplicateConflictDetector conflictDetector) {
        return new SemanticCompilationService(
                decisionService, policyProperties, conceptProperties, conflictDetector);
    }

    @Bean
    DuplicateConflictDetector duplicateConflictDetector() {
        return new ExactDuplicateConflictDetector();
    }

    @Bean
    KnowledgeIngestionService knowledgeIngestionService(
            DocumentStorage documentStorage,
            List<KnowledgeStructureExtractor> structureExtractors,
            KnowledgeRepository knowledgeRepository,
            SemanticCompilationService semanticCompilationService,
            TransactionTemplate transactionTemplate,
            IdentityProvider identityProvider,
            AuthorizationService authorizationService,
            KnowledgeUploadProperties uploadProperties) {
        return new KnowledgeIngestionService(
                documentStorage,
                structureExtractors,
                knowledgeRepository,
                semanticCompilationService,
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
            TransactionTemplate transactionTemplate,
            IdentityProvider identityProvider,
            AuthorizationService authorizationService) {
        return new KnowledgePublicationService(
                knowledgeRepository, transactionTemplate, identityProvider, authorizationService);
    }

    @Bean
    ExtractionUnitConceptClassifier extractionUnitConceptClassifier(
            KnowledgeRepository knowledgeRepository,
            SemanticDecisionService semanticDecisionService,
            DecisionSpec extractionUnitConceptSpec,
            ConceptClassificationProperties properties,
            IdentityProvider identityProvider,
            AuthorizationService authorizationService) {
        return new ExtractionUnitConceptClassifier(
                knowledgeRepository, semanticDecisionService, extractionUnitConceptSpec,
                properties, identityProvider, authorizationService);
    }
}
