package com.rivell.workbench.review;

import java.time.Clock;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;

import com.rivell.workbench.contracts.ContractEnrichmentService;
import com.rivell.workbench.contracts.ContractQueryService;
import com.rivell.workbench.intelligence.EmbeddingService;
import com.rivell.workbench.intelligence.ModelBindingCatalog;
import com.rivell.workbench.knowledge.KnowledgeQueryService;
import com.rivell.workbench.organization.AuthorizationService;
import com.rivell.workbench.organization.IdentityProvider;
import com.rivell.workbench.retrieval.RetrievalProperties;
import com.rivell.workbench.review.check.PresenceCheckExecutor;
import com.rivell.workbench.review.check.ReviewCheckExecutor;
import com.rivell.workbench.review.check.ReviewCheckExecutors;

@Configuration(proxyBeanMethods = false)
class ReviewConfiguration {

    @Bean
    FindingAssembler findingAssembler() {
        return new FindingAssembler();
    }

    @Bean
    ReviewPackRegistry reviewPackRegistry() {
        return new ReviewPackRegistry();
    }

    @Bean
    ReviewPlanBuilder reviewPlanBuilder(ReviewPackRegistry reviewPackRegistry) {
        return new ReviewPlanBuilder(reviewPackRegistry);
    }

    @Bean
    ReviewService reviewService(
            IdentityProvider identityProvider,
            AuthorizationService authorizationService,
            ContractQueryService contractQueryService,
            ContractEnrichmentService contractEnrichmentService,
            KnowledgeQueryService knowledgeQueryService,
            ModelBindingCatalog modelBindingCatalog,
            EmbeddingService embeddingService,
            RetrievalProperties retrievalProperties,
            ReviewPackRegistry reviewPackRegistry,
            ReviewPlanBuilder reviewPlanBuilder,
            ReviewRepository reviewRepository,
            TransactionTemplate transactionTemplate,
            ApplicationEventPublisher eventPublisher,
            Clock clock) {
        return new ReviewService(identityProvider, authorizationService,
                contractQueryService, contractEnrichmentService, knowledgeQueryService,
                modelBindingCatalog, embeddingService, retrievalProperties,
                reviewPackRegistry, reviewPlanBuilder, reviewRepository,
                transactionTemplate, eventPublisher, clock);
    }

    @Bean
    ReviewCheckExecutor presenceCheckExecutor() {
        return new PresenceCheckExecutor();
    }

    @Bean
    ReviewCheckExecutor confidentialitySurvivalCheckExecutor() {
        return new com.rivell.workbench.review.check.ConfidentialitySurvivalCheckExecutor();
    }

    @Bean
    ReviewCheckExecutor carveOutConsistencyCheckExecutor(
            com.rivell.workbench.intelligence.SemanticDecisionService semanticDecisionService) {
        return new com.rivell.workbench.review.check.CarveOutConsistencyCheckExecutor(
                semanticDecisionService);
    }

    @Bean
    ReviewCheckExecutor liabilityCapCheckExecutor(
            com.rivell.workbench.intelligence.SemanticDecisionService semanticDecisionService,
            com.rivell.workbench.intelligence.GenerativeReasoningService generativeReasoningService) {
        return new com.rivell.workbench.review.check.LiabilityCapCheckExecutor(
                semanticDecisionService, generativeReasoningService);
    }

    @Bean
    ReviewCheckExecutors reviewCheckExecutors(List<ReviewCheckExecutor> executors) {
        return new ReviewCheckExecutors(executors);
    }

    @Bean
    ReviewAnalysisService reviewAnalysisService(
            ReviewRepository reviewRepository,
            ReviewAnalysisRepository reviewAnalysisRepository,
            ReviewCheckExecutors reviewCheckExecutors,
            ContractEnrichmentService contractEnrichmentService,
            RetrievalProperties retrievalProperties,
            EmbeddingService embeddingService,
            com.rivell.workbench.retrieval.EvidenceService evidenceService,
            ModelBindingCatalog modelBindingCatalog,
            FindingRepository findingRepository,
            FindingAssembler findingAssembler,
            TransactionTemplate transactionTemplate,
            Clock clock) {
        return new ReviewAnalysisService(reviewRepository, reviewAnalysisRepository,
                reviewCheckExecutors, contractEnrichmentService, retrievalProperties,
                embeddingService, evidenceService, modelBindingCatalog, findingRepository,
                findingAssembler, transactionTemplate, clock);
    }

    @Bean
    com.rivell.workbench.review.api.ReviewApiService reviewApiService(
            ReviewService reviewService,
            ReviewAnalysisService reviewAnalysisService,
            ReviewRepository reviewRepository,
            ReviewAnalysisRepository reviewAnalysisRepository,
            FindingRepository findingRepository,
            ContractQueryService contractQueryService,
            ReviewPackRegistry reviewPackRegistry,
            ReviewListQuery reviewListQuery,
            ReviewHumanReviewListQuery reviewHumanReviewListQuery,
            ReviewDocumentQuery reviewDocumentQuery,
            IdentityProvider identityProvider,
            AuthorizationService authorizationService) {
        return new com.rivell.workbench.review.api.ReviewApiService(
                reviewService, reviewAnalysisService, reviewRepository,
                reviewAnalysisRepository, findingRepository, contractQueryService,
                    reviewPackRegistry, reviewListQuery,
                reviewHumanReviewListQuery, reviewDocumentQuery, identityProvider,
                authorizationService);
    }
}
