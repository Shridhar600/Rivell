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
            RetrievalProperties properties) {
        return new PrecedentLane(resolver, knowledgeQueries, properties);
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
            Clock clock) {
        return new EvidenceService(resolver, precedentLane, currentContractLane,
                retrievalRepository, knowledgeQueries, clock);
    }
}
