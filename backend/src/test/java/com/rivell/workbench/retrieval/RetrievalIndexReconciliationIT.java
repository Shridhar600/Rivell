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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.rivell.workbench.organization.IdentityProvider;
import com.rivell.workbench.organization.UserIdentity;

/**
 * Reconciliation flow (DW-012): a published precedent whose derived index rows were
 * lost is honestly reported (INDEX_INCOMPLETE / PARTIAL), healed by one bounded
 * reconciliation pass (lexical + embedding restored), then discovered normally.
 * A second pass is a no-op (no duplicate rows), and another organization's index
 * state is never touched.
 */
@SpringBootTest
@Testcontainers
class RetrievalIndexReconciliationIT {

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
    RetrievalIndexReconciliationService reconciliationService;

    @Autowired
    org.springframework.jdbc.core.simple.JdbcClient jdbcClient;

    @BeforeEach
    void freshOrganization() {
        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), java.util.Set.of("ADMIN")));
    }

    private EvidenceRequest subjectRequest(M6EvidenceCorpus.Corpus corpus) {
        var organizationId = identityProvider.currentIdentity().organizationId();
        var clauseId = corpus.semantics().clauses().stream()
                .filter(clause -> clause.primaryConcept().isPresent()
                        && clause.primaryConcept().get().equals(CoreConcepts.LIMITATION_OF_LIABILITY))
                .findFirst().orElseThrow().id();
        return EvidenceRequest.forClauseReview(
                organizationId, CoreConcepts.LIMITATION_OF_LIABILITY,
                corpus.contract().contractId(), 1, clauseId,
                M6EvidenceCorpus.SUBJECT_CLAUSE_TEXT, M6EvidenceCorpus.msaProfile(),
                LocalDate.of(2026, 9, 24), KnowledgePin.LATEST);
    }

    private int indexRowCount(UUID organizationId) {
        return jdbcClient.sql("""
                        select count(*) from retrieval_evidence_index
                        where organization_id = ?
                        """)
                .param(organizationId)
                .query((rs, i) -> rs.getInt(1))
                .list().get(0);
    }

    @Test
    void reconciliationHealsMissingIndexStateWithoutDuplicatesAndAcrossTenants() {
        var organizationA = identityProvider.currentIdentity().organizationId();
        var corpusA = corpusBuilder.buildMainCorpus(organizationA);
        // org B: an independent published corpus that must stay untouched
        identityProvider.use(new UserIdentity(UUID.randomUUID(), UUID.randomUUID(),
                java.util.Set.of("ADMIN")));
        var organizationB = identityProvider.currentIdentity().organizationId();
        corpusBuilder.buildMainCorpus(organizationB);
        identityProvider.use(new UserIdentity(UUID.randomUUID(), organizationA,
                java.util.Set.of("ADMIN")));

        var requestA = subjectRequest(corpusA);
        var healthy = evidenceService.build(requestA);
        assertThat(healthy.degradationWarnings()).doesNotContain("INDEX_INCOMPLETE");

        // simulate an indexing failure: lose the derived index rows for org A only
        jdbcClient.sql("delete from retrieval_evidence_index where organization_id = ?")
                .param(organizationA).update();

        var degraded = evidenceService.build(requestA);
        assertThat(degraded.degradationWarnings()).contains("INDEX_INCOMPLETE");
        assertThat(degraded.sufficiency()).isEqualTo(EvidenceSufficiency.PARTIAL);
        assertThat(degraded.precedentItems()).isEmpty();

        // reconciliation heals the derived index (bounded pass, embeddings included)
        var first = reconciliationService.reconcile(100);
        assertThat(first.rowsIndexed()).isGreaterThan(0);
        assertThat(first.embeddingsPersisted()).isGreaterThan(0);
        assertThat(first.failed()).isEqualTo(0);

        var healed = evidenceService.build(requestA);
        assertThat(healed.degradationWarnings()).doesNotContain("INDEX_INCOMPLETE");
        assertThat(healed.precedentItems()).isNotEmpty();

        // idempotency: a second pass writes nothing new
        var rowsAfterFirst = indexRowCount(organizationA);
        var second = reconciliationService.reconcile(100);
        assertThat(second.rowsIndexed()).isEqualTo(0);
        assertThat(indexRowCount(organizationA)).isEqualTo(rowsAfterFirst);

        // tenant boundaries: org B's index state was never touched by the healing
        var orgBRowsBefore = indexRowCount(organizationB);
        reconciliationService.reconcile(100);
        assertThat(indexRowCount(organizationB)).isEqualTo(orgBRowsBefore);
    }
}
