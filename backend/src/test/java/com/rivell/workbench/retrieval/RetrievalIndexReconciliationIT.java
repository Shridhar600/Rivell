package com.rivell.workbench.retrieval;

import com.rivell.workbench.taxonomy.CoreConcepts;
import com.rivell.workbench.knowledge.CandidateType;
import com.rivell.workbench.knowledge.KnowledgeCandidate;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
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
        // one page of reconciliation is one batch: a corpus larger than this page must still be
        // walked to its oldest row within the pass
        registry.add("rivell.retrieval.reconciliation-batch-size", () -> "50");
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
    RetrievalRepository repository;

    @Autowired
    RetrievalProperties properties;

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
        assertThat(degraded.degradationWarnings()).contains("INDEX_INCOMPLETE")
                // A row that is not in the index at all has no vector to report as missing.
                .doesNotContain("VECTOR_INDEX_INCOMPLETE");
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

    @Test
    void reconciliationReembedsRowsThatExistWithoutVectors() {
        var organizationId = identityProvider.currentIdentity().organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);
        var request = subjectRequest(corpus);
        var healthy = evidenceService.build(request);
        assertThat(healthy.degradationWarnings()).doesNotContain("VECTOR_INDEX_INCOMPLETE");
        // A precedent the lane actually narrowed, so its vector gap is the lane's gap.
        var precedent = healthy.precedentItems().getFirst().sourceCandidateId();
        assertThat(precedent).isNotNull();

        // An embedding failure leaves the row and its text searchable, but the vector lane blind.
        jdbcClient.sql("""
                        update retrieval_evidence_index
                        set embedding = null, embedding_model = null,
                            embedding_dims = null, embedding_index_ready = false
                        where organization_id = ? and candidate_id = ?
                        """)
                .param(organizationId).param(precedent).update();
        assertThat(repository.findEmbeddingReadyIds(organizationId, List.of(precedent),
                properties.indexVersion())).isEmpty();
        assertThat(evidenceService.build(request).degradationWarnings())
                .contains("VECTOR_INDEX_INCOMPLETE");
        var rowsBefore = indexRowCount(organizationId);

        var result = reconciliationService.reconcile(500);

        // The row already existed, so nothing is indexed; the missing vector is the work.
        assertThat(result.rowsIndexed()).isZero();
        assertThat(result.embeddingsPersisted()).isGreaterThan(0);
        assertThat(indexRowCount(organizationId)).isEqualTo(rowsBefore);
        assertThat(repository.findEmbeddingReadyIds(organizationId, List.of(precedent),
                properties.indexVersion())).containsExactly(precedent);
        assertThat(evidenceService.build(request).degradationWarnings())
                .doesNotContain("VECTOR_INDEX_INCOMPLETE");

        var second = reconciliationService.reconcile(500);
        assertThat(second.rowsIndexed()).isZero();
        assertThat(second.embeddingsPersisted()).isZero();
    }

    @Test
    void publishedPlaybookPositionsAreSearchable() {
        var organizationId = identityProvider.currentIdentity().organizationId();
        // every position of the playbook is published (the main corpus publishes only one)
        var positions = corpusBuilder.publishAll(organizationId,
                        M6EvidenceCorpus.Fixture.POLICY_MAIN, M6EvidenceCorpus.msaManifest2026(), null)
                .stream()
                .filter(candidate -> candidate.candidateType() == CandidateType.PLAYBOOK_RULE)
                .toList();
        assertThat(positions).hasSizeGreaterThanOrEqualTo(2);

        var positionIds = positions.stream().map(KnowledgeCandidate::id).toList();
        var labelsById = positions.stream().collect(java.util.stream.Collectors.toMap(
                KnowledgeCandidate::id, KnowledgeCandidate::rawLabel));

        assertThat(repository.findIndexedIds(organizationId, positionIds, properties.indexVersion()))
                .as("indexed playbook positions %s", labelsById)
                .containsAll(positionIds);

        assertThat(repository.findEmbeddingReadyIds(
                organizationId, positionIds, properties.indexVersion()))
                .as("embedding-ready playbook positions %s", labelsById)
                .containsAll(positionIds);

        var searchPosition = positions.get(0);
        var otherTexts = positions.stream()
                .filter(candidate -> !candidate.id().equals(searchPosition.id()))
                .map(KnowledgeCandidate::effectiveContentText)
                .toList();
        var word = distinctiveWord(searchPosition.effectiveContentText(), otherTexts);
        var lexicalHits = repository.findLexicalCandidates(organizationId, positionIds,
                LexicalQueries.orTerms(word), 5, properties.indexVersion());
        assertThat(lexicalHits).as("lexical hits for '%s'", word).isNotEmpty();
        assertThat(lexicalHits.get(0).candidateId()).isEqualTo(searchPosition.id());

        // the healing path restores lost index rows for published positions too
        jdbcClient.sql("delete from retrieval_evidence_index where organization_id = ?")
                .param(organizationId).update();
        reconciliationService.reconcile(500);
        assertThat(repository.findIndexedIds(organizationId, positionIds, properties.indexVersion()))
                .as("healed playbook positions %s", labelsById)
                .containsAll(positionIds);
    }

    @Test
    void aPassReachesTheWholePublishedCorpusNotJustTheNewestPage() {
        var organizationId = identityProvider.currentIdentity().organizationId();
        var positions = new java.util.ArrayList<KnowledgeCandidate>();
        while (positions.size() < 60) {
            positions.addAll(corpusBuilder.publishAll(organizationId,
                            M6EvidenceCorpus.Fixture.PC_PLAYBOOK,
                            M6EvidenceCorpus.msaManifest2026(), null).stream()
                    .filter(candidate -> candidate.candidateType() == CandidateType.PLAYBOOK_RULE)
                    .toList());
        }
        // the oldest position is older than every row of the first page
        var oldest = jdbcClient.sql("""
                        select id from knowledge_candidate
                        where organization_id = ? and candidate_type = 'PLAYBOOK_RULE'
                        order by created_at, id
                        limit 1
                        """)
                .param(organizationId)
                .query(UUID.class)
                .single();
        assertThat(positions).extracting(KnowledgeCandidate::id).contains(oldest);
        var deleted = jdbcClient.sql("""
                        delete from retrieval_evidence_index
                        where organization_id = ? and candidate_id = ?
                        """)
                .param(organizationId).param(oldest).update();
        assertThat(deleted).isEqualTo(1);

        var result = reconciliationService.reconcile(properties.reconciliationBatchSize());

        assertThat(result.rowsIndexed()).isEqualTo(1);
        assertThat(repository.findIndexedIds(organizationId, List.of(oldest),
                properties.indexVersion())).containsExactly(oldest);
    }

    private static String distinctiveWord(String text, List<String> otherTexts) {
        for (var token : text.split("\\s+")) {
            var word = token.replaceAll("[^A-Za-z]", "");
            if (word.length() < 5) {
                continue;
            }
            var lower = word.toLowerCase(java.util.Locale.ROOT);
            if (otherTexts.stream().noneMatch(
                    other -> other.toLowerCase(java.util.Locale.ROOT).contains(lower))) {
                return word;
            }
        }
        throw new IllegalStateException("No distinctive word in: " + text);
    }
}
