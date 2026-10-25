package com.rivell.workbench.knowledge;

import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;

import com.rivell.workbench.foundation.DocumentStorage;
import com.rivell.workbench.foundation.DocumentWorkLimiter;
import com.rivell.workbench.intelligence.SemanticDecisionService;
import com.rivell.workbench.organization.AuthorizationService;
import com.rivell.workbench.organization.IdentityProvider;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
        KnowledgeUploadProperties.class, ConceptClassificationProperties.class,
        SemanticPolicyProperties.class})
class KnowledgeServicesConfiguration {

    @Bean
    GenerativeFieldExtractor generativeFieldExtractor(
            com.rivell.workbench.intelligence.GenerativeReasoningService reasoningService,
            com.rivell.workbench.intelligence.SemanticDecisionService decisionService,
            SemanticPolicyProperties policyProperties) {
        return new GenerativeFieldExtractor(reasoningService, decisionService, policyProperties);
    }

    @Bean
    CandidateReviewCategorizer candidateReviewCategorizer(
            SemanticPolicyProperties properties,
            ConceptClassificationProperties conceptProperties,
            com.rivell.workbench.taxonomy.ConceptRegistry conceptRegistry) {
        return new CandidateReviewCategorizer(properties, conceptProperties, conceptRegistry);
    }

    @Bean
    SemanticCompilationService semanticCompilationService(
            com.rivell.workbench.intelligence.SemanticDecisionService decisionService,
            SemanticPolicyProperties policyProperties,
            DuplicateConflictDetector conflictDetector,
            GenerativeFieldExtractor generativeFieldExtractor,
            CandidateReviewCategorizer categorizer,
            com.rivell.workbench.taxonomy.TopicClassifier topicClassifier) {
        return new SemanticCompilationService(
                decisionService, policyProperties, conflictDetector,
                generativeFieldExtractor, categorizer, topicClassifier);
    }

    @Bean
    DuplicateConflictDetector duplicateConflictDetector(
            com.rivell.workbench.intelligence.SemanticDecisionService decisionService,
            SemanticPolicyProperties policyProperties) {
        return new SemanticConflictAnalyzer(decisionService, policyProperties);
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
            DocumentWorkLimiter documentWorkLimiter,
            KnowledgeUploadProperties uploadProperties) {
        return new KnowledgeIngestionService(
                documentStorage,
                structureExtractors,
                knowledgeRepository,
                semanticCompilationService,
                transactionTemplate,
                identityProvider,
                authorizationService,
                documentWorkLimiter,
                uploadProperties.maxFileSize());
    }

    @Bean
    KnowledgeQueryService knowledgeQueryService(
            KnowledgeRepository knowledgeRepository,
            IdentityProvider identityProvider,
            AuthorizationService authorizationService,
            java.time.Clock clock) {
        return new KnowledgeQueryService(
                knowledgeRepository, identityProvider, authorizationService, clock);
    }

    @Bean
    SourceApplicabilityRederivationService sourceApplicabilityRederivationService(
            KnowledgeRepository knowledgeRepository, java.time.Clock clock) {
        return new SourceApplicabilityRederivationService(knowledgeRepository, clock);
    }

    @Bean
    KnowledgeTitleRederivationService knowledgeTitleRederivationService(
            KnowledgeRepository knowledgeRepository, java.time.Clock clock) {
        return new KnowledgeTitleRederivationService(knowledgeRepository, clock);
    }

    @Bean
    CandidateReviewService candidateReviewService(
            KnowledgeRepository knowledgeRepository,
            TransactionTemplate transactionTemplate,
            IdentityProvider identityProvider,
            AuthorizationService authorizationService,
            DuplicateConflictDetector conflictDetector,
            CandidateReviewCategorizer categorizer,
            com.rivell.workbench.taxonomy.ConceptRegistry conceptRegistry) {
        return new CandidateReviewService(
                knowledgeRepository, transactionTemplate, identityProvider,
                authorizationService, conflictDetector, categorizer, conceptRegistry);
    }

    @Bean
    UserPositionService userPositionService(
            KnowledgeRepository knowledgeRepository,
            SemanticCompilationService semanticCompilationService,
            TransactionTemplate transactionTemplate,
            IdentityProvider identityProvider,
            AuthorizationService authorizationService) {
        return new UserPositionService(knowledgeRepository, semanticCompilationService,
                transactionTemplate, identityProvider, authorizationService);
    }

    @Bean
    KnowledgePublicationService knowledgePublicationService(
            KnowledgeRepository knowledgeRepository,
            TransactionTemplate transactionTemplate,
            IdentityProvider identityProvider,
            AuthorizationService authorizationService,
            org.springframework.context.ApplicationEventPublisher eventPublisher) {
        return new KnowledgePublicationService(
                knowledgeRepository, transactionTemplate, identityProvider,
                authorizationService, eventPublisher);
    }

    @Bean
    ExtractionUnitConceptClassifier extractionUnitConceptClassifier(
            KnowledgeRepository knowledgeRepository,
            com.rivell.workbench.taxonomy.TopicClassifier topicClassifier,
            IdentityProvider identityProvider,
            AuthorizationService authorizationService) {
        return new ExtractionUnitConceptClassifier(
                knowledgeRepository, topicClassifier, identityProvider, authorizationService);
    }
}
