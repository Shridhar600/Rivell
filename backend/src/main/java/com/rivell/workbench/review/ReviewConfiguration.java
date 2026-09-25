package com.rivell.workbench.review;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.rivell.workbench.contracts.ContractEnrichmentService;
import com.rivell.workbench.contracts.ContractQueryService;
import com.rivell.workbench.intelligence.EmbeddingService;
import com.rivell.workbench.intelligence.ModelBindingCatalog;
import com.rivell.workbench.knowledge.KnowledgeQueryService;
import com.rivell.workbench.organization.AuthorizationService;
import com.rivell.workbench.organization.IdentityProvider;
import com.rivell.workbench.retrieval.RetrievalProperties;

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
            Clock clock) {
        return new ReviewService(identityProvider, authorizationService,
                contractQueryService, contractEnrichmentService, knowledgeQueryService,
                modelBindingCatalog, embeddingService, retrievalProperties,
                reviewPackRegistry, reviewPlanBuilder, reviewRepository, clock);
    }
}
