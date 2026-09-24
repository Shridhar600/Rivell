package com.rivell.workbench.retrieval;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.rivell.workbench.contracts.ContractEnrichmentService;
import com.rivell.workbench.intelligence.EmbeddingService;
import com.rivell.workbench.intelligence.SemanticDecisionService;
import com.rivell.workbench.knowledge.KnowledgeQueryService;
import com.rivell.workbench.retrieval.RetrievalRepository;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RetrievalProperties.class)
class RetrievalServicesConfiguration {

    @Bean
    StructuredPolicyResolver structuredPolicyResolver(KnowledgeQueryService knowledgeQueries) {
        return new StructuredPolicyResolver(knowledgeQueries);
    }

    @Bean
    PrecedentLane precedentLane(
            StructuredPolicyResolver resolver,
            KnowledgeQueryService knowledgeQueries,
            RetrievalRepository retrievalRepository,
            EmbeddingService embeddingService,
            SemanticDecisionService decisionService,
            RetrievalProperties properties) {
        return new PrecedentLane(resolver, knowledgeQueries, retrievalRepository,
                embeddingService, decisionService, properties);
    }

    @Bean
    CurrentContractLane currentContractLane(ContractEnrichmentService enrichmentService) {
        return new CurrentContractLane(enrichmentService);
    }

    @Bean
    EvidenceIndexService evidenceIndexService(
            KnowledgeQueryService knowledgeQueries,
            EmbeddingService embeddingService,
            RetrievalRepository retrievalRepository,
            RetrievalProperties properties) {
        return new EvidenceIndexService(knowledgeQueries, embeddingService, retrievalRepository,
                properties);
    }

    @Bean
    EvidenceService evidenceService(
            StructuredPolicyResolver resolver,
            PrecedentLane precedentLane,
            CurrentContractLane currentContractLane,
            RetrievalRepository retrievalRepository,
            KnowledgeQueryService knowledgeQueries,
            EmbeddingService embeddingService,
            com.rivell.workbench.organization.IdentityProvider identityProvider,
            com.rivell.workbench.organization.AuthorizationService authorizationService,
            Clock clock) {
        return new EvidenceService(resolver, precedentLane, currentContractLane,
                retrievalRepository, knowledgeQueries, embeddingService, identityProvider,
                authorizationService, clock);
    }

    @Bean
    com.rivell.workbench.retrieval.api.EvidenceApiService evidenceApiService(
            EvidenceService evidenceService,
            com.rivell.workbench.organization.IdentityProvider identityProvider,
            com.rivell.workbench.contracts.ContractEnrichmentService enrichmentService) {
        return new com.rivell.workbench.retrieval.api.EvidenceApiService(
                evidenceService, identityProvider, enrichmentService);
    }

    @Bean
    com.rivell.workbench.retrieval.api.TraceApiService traceApiService(
            RetrievalRepository retrievalRepository,
            com.rivell.workbench.organization.IdentityProvider identityProvider,
            com.rivell.workbench.organization.AuthorizationService authorizationService) {
        return new com.rivell.workbench.retrieval.api.TraceApiService(
                retrievalRepository, identityProvider, authorizationService);
    }
}
