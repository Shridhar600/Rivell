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
    ReviewCheckExecutor liabilityCapCheckExecutor(
            com.rivell.workbench.retrieval.EvidenceService evidenceService,
            com.rivell.workbench.intelligence.SemanticDecisionService semanticDecisionService) {
        return new com.rivell.workbench.review.check.LiabilityCapCheckExecutor(
                evidenceService, semanticDecisionService);
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
            TransactionTemplate transactionTemplate,
            Clock clock) {
        return new ReviewAnalysisService(reviewRepository, reviewAnalysisRepository,
                reviewCheckExecutors, contractEnrichmentService, retrievalProperties,
                embeddingService, transactionTemplate, clock);
    }
}
