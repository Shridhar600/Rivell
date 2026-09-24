package com.rivell.workbench.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.rivell.workbench.contracts.LegalConcept;
import com.rivell.workbench.organization.IdentityProvider;
import com.rivell.workbench.organization.UserIdentity;

/**
 * Rerank budget branch: when the fused candidate set exceeds the rerank budget,
 * un-evaluated candidates are rejected with an honest trace reason.
 */
@SpringBootTest
@Testcontainers
class PrecedentRerankBudgetIT {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("pgvector/pgvector:0.8.6-pg17-trixie")
            .asCompatibleSubstituteFor("postgres");

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("rivell")
            .withUsername("rivell")
            .withPassword("rivell");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("rivell.storage.local.root",
                () -> "target/test-documents/" + UUID.randomUUID());
        registry.add("rivell.retrieval.indexing-enabled", () -> "true");
        registry.add("rivell.retrieval.rerank-budget", () -> "1");
        registry.add("rivell.intelligence.embeddings.api-key", () -> "fake-key");
        registry.add("rivell.intelligence.embeddings.provider", () -> "fake");
        registry.add("rivell.intelligence.embeddings.model", () -> "fake-embedding-model");
        registry.add("rivell.intelligence.embeddings.dimensions", () -> "8");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeIntelligenceConfiguration {

        @Bean
        @Primary
        com.rivell.workbench.intelligence.ModelPolicy fakeModelPolicy() {
            return asset -> asset instanceof com.rivell.workbench.intelligence.ReasoningSpec
                    ? new com.rivell.workbench.intelligence.ModelBinding("fake", "fake-gen-model")
                    : new com.rivell.workbench.intelligence.ModelBinding("fake", "fake-model-latest");
        }

        @Bean
        @Primary
        com.rivell.workbench.intelligence.SemanticDecisionProvider fakeSemanticDecisionProvider() {
            return new CorpusScriptedProvider();
        }

        @Bean
        @Primary
        com.rivell.workbench.intelligence.GenerativeReasoningProvider fakeGenerativeProvider() {
            return new com.rivell.workbench.contracts.ContractEnrichmentIT.M5FakeGenerativeProvider();
        }

        @Bean
        @Primary
        com.rivell.workbench.intelligence.EmbeddingProvider fakeEmbeddingProvider() {
            return new FakeEmbeddingProvider();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MutableIdentityConfiguration {

        @Bean
        @Primary
        EvidenceApiServiceIT.MutableIdentity mutableIdentityProvider() {
            return new EvidenceApiServiceIT.MutableIdentity();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CorpusConfiguration {

        @Bean
        M6EvidenceCorpus m6EvidenceCorpus(
                com.rivell.workbench.knowledge.KnowledgeIngestionService ingestionService,
                com.rivell.workbench.knowledge.CandidateReviewService reviewService,
                com.rivell.workbench.knowledge.KnowledgePublicationService publicationService,
                com.rivell.workbench.knowledge.KnowledgeQueryService queryService,
                com.rivell.workbench.contracts.ContractIngestionService contractIngestionService,
                com.rivell.workbench.contracts.ContractEnrichmentService enrichmentService) {
            return new M6EvidenceCorpus(ingestionService, reviewService, publicationService,
                    queryService, contractIngestionService, enrichmentService);
        }
    }

    @Autowired
    EvidenceApiServiceIT.MutableIdentity identityProvider;

    @Autowired
    M6EvidenceCorpus corpusBuilder;

    @Autowired
    EvidenceService evidenceService;

    @Autowired
    RetrievalRepository retrievalRepository;

    @BeforeEach
    void freshOrganization() {
        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), java.util.Set.of("ADMIN")));
    }

    @Test
    void fusedCandidatesBeyondTheRerankBudgetAreRejectedWithReason() {
        var organizationId = identityProvider.currentIdentity().organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);
        var clauseId = corpus.semantics().clauses().stream()
                .filter(clause -> clause.primaryConcept().isPresent()
                        && clause.primaryConcept().get() == LegalConcept.LIMITATION_OF_LIABILITY)
                .findFirst().orElseThrow().id();
        var request = EvidenceRequest.forClauseReview(
                organizationId, LegalConcept.LIMITATION_OF_LIABILITY,
                corpus.contract().contractId(), 1, clauseId,
                M6EvidenceCorpus.SUBJECT_CLAUSE_TEXT, M6EvidenceCorpus.msaProfile(),
                LocalDate.of(2026, 9, 24), KnowledgePin.LATEST);

        var bundle = evidenceService.build(request);

        assertThat(bundle.precedentItems()).hasSize(1);
        var trace = retrievalRepository
                .findTrace(organizationId, bundle.traceId()).orElseThrow();
        assertThat(trace.rejectedEvidence())
                .anySatisfy(rejected -> assertThat(rejected.reason())
                        .isEqualTo("candidate budget exhausted"));
        assertThat(trace.rejectedEvidence().size()).isGreaterThanOrEqualTo(1);
    }
}
