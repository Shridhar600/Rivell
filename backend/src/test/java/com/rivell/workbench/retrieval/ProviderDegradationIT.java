package com.rivell.workbench.retrieval;

import com.rivell.workbench.taxonomy.CoreConcepts;

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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.rivell.workbench.organization.IdentityProvider;
import com.rivell.workbench.organization.UserIdentity;

/**
 * Degradation semantics (spec §6, acceptance flow 12): with the semantic provider
 * unavailable, the precedent lane passes fused candidates through, records
 * honest degradation, and sufficiency never silently claims full sufficiency.
 */
@SpringBootTest
@Testcontainers
class ProviderDegradationIT {

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
        // the fake embedding provider keeps the vector lane alive while the DECISION
        // provider is unreachable below
        registry.add("rivell.intelligence.embeddings.api-key", () -> "fake-key");
        registry.add("rivell.intelligence.embeddings.provider", () -> "fake");
        registry.add("rivell.intelligence.embeddings.model", () -> "fake-embedding-model");
        registry.add("rivell.intelligence.embeddings.dimensions", () -> "8");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FailingDecisionConfiguration {

        @Bean
        @Primary
        com.rivell.workbench.intelligence.ModelPolicy failingDecisionModelPolicy() {
            // M4/M5 semantic compilation stays on "fake"; ONLY the M6 retrieval specs
            // route to an unconfigured provider so their judgments degrade
            return asset -> {
                if (asset instanceof com.rivell.workbench.intelligence.ReasoningSpec) {
                    return new com.rivell.workbench.intelligence.ModelBinding("fake", "fake-gen-model");
                }
                if (asset instanceof com.rivell.workbench.intelligence.DecisionSpec spec
                        && spec.id().startsWith("precedent-")) {
                    return new com.rivell.workbench.intelligence.ModelBinding(
                            "unconfigured-provider", "any-model");
                }
                return new com.rivell.workbench.intelligence.ModelBinding("fake", "fake-model-latest");
            };
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
        MutableIdentity mutableIdentityProvider() {
            return new MutableIdentity();
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

    static final class MutableIdentity implements IdentityProvider {

        volatile UserIdentity current = new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), java.util.Set.of("ADMIN"));

        void use(UserIdentity identity) {
            current = identity;
        }

        @Override
        public UserIdentity currentIdentity() {
            return current;
        }
    }

    @Autowired
    MutableIdentity identityProvider;

    @Autowired
    M6EvidenceCorpus corpusBuilder;

    @Autowired
    EvidenceService evidenceService;

    @Autowired
    JdbcClient jdbcClient;

    @BeforeEach
    void freshOrganization() {
        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), java.util.Set.of("ADMIN")));
    }

    @Test
    void rerankAndCompatibilityDegradationPassesEvidenceThroughHonestly() {
        var organizationId = identityProvider.currentIdentity().organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);

        var clauseId = corpus.semantics().clauses().stream()
                .filter(clause -> clause.primaryConcept().isPresent()
                        && clause.primaryConcept().get().equals(CoreConcepts.LIMITATION_OF_LIABILITY))
                .findFirst().orElseThrow().id();
        var request = EvidenceRequest.forClauseReview(
                identityProvider.currentIdentity().organizationId(),
                CoreConcepts.LIMITATION_OF_LIABILITY,
                corpus.contract().contractId(), 1, clauseId,
                M6EvidenceCorpus.SUBJECT_CLAUSE_TEXT, M6EvidenceCorpus.msaProfile(),
                java.time.LocalDate.of(2026, 9, 24),
                KnowledgePin.at(corpus.latestPublicationNumber()));

        var bundle = evidenceService.build(request);

        // fused candidates still surface as evidence — retrieval degrades, not fails
        assertThat(bundle.precedentItems()).isNotEmpty();
        // no Jev verdicts were fabricated
        assertThat(bundle.precedentItems())
                .allSatisfy(item -> {
                    assertThat(item.semanticAssessment()).isNull();
                    assertThat(item.policyCompatibility())
                            .isEqualTo(PolicyCompatibility.NOT_ASSESSED);
                });
        // degradation is honest: partial, never silent full sufficiency
        assertThat(bundle.sufficiency()).isEqualTo(EvidenceSufficiency.PARTIAL);
        // the bundle surfaces the same degradation flags the API contract promises
        assertThat(bundle.degradationWarnings())
                .contains("RERANK_UNAVAILABLE", "COMPATIBILITY_UNAVAILABLE");
        // the trace carries the degradation flags
        var trace = jdbcClient.sql(
                        "select degradation from retrieval_trace where id = ?")
                .param(bundle.traceId())
                .query((rs, i) -> rs.getString("degradation"))
                .list().get(0);
        assertThat(trace).contains("RERANK_UNAVAILABLE").contains("COMPATIBILITY_UNAVAILABLE");
    }
}
