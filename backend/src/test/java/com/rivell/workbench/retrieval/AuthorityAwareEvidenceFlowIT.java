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
import com.rivell.workbench.retrieval.M6EvidenceCorpus.Corpus;

@SpringBootTest
@Testcontainers
class AuthorityAwareEvidenceFlowIT {

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

    @TestConfiguration(proxyBeanMethods = false)
    static class MutableIdentityConfiguration {

        @Bean
        @Primary
        MutableIdentity mutableIdentityProvider() {
            return new MutableIdentity();
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
    org.springframework.jdbc.core.simple.JdbcClient jdbcClient;

    @BeforeEach
    void freshOrganization() {
        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), java.util.Set.of("ADMIN")));
    }

    @Test
    void structuredOnlyResolvesApplicablePolicyAndApprovedDraftingWithoutEmbeddingsOrJev() {
        var organizationId = identityProvider.currentIdentity().organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);

        var request = EvidenceRequest.forClauseReview(
                organizationId,
                LegalConcept.LIMITATION_OF_LIABILITY,
                corpus.contract().contractId(),
                1,
                corpus.semantics().clauses().stream()
                        .filter(clause -> clause.primaryConcept()
                                .isPresent()
                                && clause.primaryConcept().get()
                                        == LegalConcept.LIMITATION_OF_LIABILITY)
                        .findFirst().orElseThrow().id(),
                M6EvidenceCorpus.SUBJECT_CLAUSE_TEXT,
                M6EvidenceCorpus.msaProfile(),
                LocalDate.of(2026, 9, 24),
                KnowledgePin.at(corpus.latestPublicationNumber()));

        var bundle = evidenceService.build(request);

        // the applicable policy resolves exactly once — the 2025 rule is superseded,
        // the UK/NDA/supplier/future rules are out of scope
        assertThat(bundle.policyItems()).hasSize(1);
        var policy = bundle.policyItems().get(0);
        assertThat(policy.authorityClass()).isEqualTo(AuthorityClass.POLICY);
        assertThat(policy.legalConcept()).isEqualTo(LegalConcept.LIMITATION_OF_LIABILITY);
        assertThat(policy.text()).contains("The total liability of Supplier shall not exceed");
        assertThat(policy.policyFacts()).isNotNull();

        // approved drafting preserves exact wording (preferred + fallback variants)
        assertThat(bundle.approvedDraftingItems()).isNotEmpty();
        var preferredText = "The total liability of Supplier shall not exceed the fees paid "
                + "in the preceding 12 months.";
        assertThat(bundle.approvedDraftingItems())
                .anySatisfy(item -> {
                    assertThat(item.text()).isEqualTo(preferredText);
                    assertThat(item.authorityClass()).isEqualTo(AuthorityClass.APPROVED_DRAFTING);
                });

        // current-contract evidence is the reviewed clause itself with typed facts
        assertThat(bundle.currentContractItems()).isNotEmpty();
        var subject = bundle.currentContractItems().get(0);
        assertThat(subject.sourceClauseId()).isEqualTo(request.clauseId());
        assertThat(subject.contractFacts()).isNotEmpty();
        System.out.println("REL-DEBUG subject heading=" + corpus.semantics().clauses().stream()
                .filter(c -> c.id().equals(request.clauseId())).findFirst().orElseThrow().headingNodeId());
        System.out.println("REL-DEBUG relationshipCount=" + corpus.semantics().relationships().size());
        corpus.semantics().relationships().forEach(relationship -> System.out.println(
                "REL-DEBUG type=" + relationship.type()
                        + " source=" + relationship.sourceRegionNodeId()
                        + " target=" + relationship.targetRegionNodeId()));
        System.out.println("REL-DEBUG referenceCount=" + corpus.semantics().references().size()
                + " resolved=" + corpus.semantics().references().stream()
                        .filter(com.rivell.workbench.contracts.ContractReference::resolved).count());
        // semantic relationships of the subject clause surface as related-clause evidence
        assertThat(bundle.currentContractItems().size()).isGreaterThan(1);
        assertThat(bundle.currentContractItems()).anySatisfy(item ->
                assertThat(item.retrievalReason()).startsWith("SEMANTIC_RELATIONSHIP:"));

        // deterministic sufficiency for the complete structured state
        assertThat(bundle.sufficiency()).isEqualTo(EvidenceSufficiency.SUFFICIENT);
        assertThat(bundle.traceId()).isNotNull();

        // the approved-but-unpublished and rejected candidates never surface
        assertThat(bundle.items())
                .noneSatisfy(item -> assertThat(item.sourceCandidateId())
                        .isIn(corpus.approvedNotPublished().stream()
                                .map(candidate -> candidate.id())
                                .toList()))
                .noneSatisfy(item -> assertThat(item.sourceCandidateId())
                        .isIn(corpus.rejected().stream()
                                .map(candidate -> candidate.id())
                                .toList()));
    }

    @Test
    void publicationIndexesPrecedentEvidenceIntoTheRetrievalIndex() {
        var organizationId = identityProvider.currentIdentity().organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);

        var indexedRows = jdbcClient.sql(
                        "select count(*) from retrieval_evidence_index where organization_id = ?")
                .param(organizationId)
                .query(Integer.class)
                .list().get(0);
        assertThat(indexedRows).isGreaterThan(0);

        // embedding generation is unconfigured in this test environment: rows stay
        // lexically searchable while the vector lane is honestly not-ready
        var vectorReady = jdbcClient.sql(
                        "select count(*) from retrieval_evidence_index"
                                + " where organization_id = ? and embedding_index_ready")
                .param(organizationId)
                .query(Integer.class)
                .list().get(0);
        assertThat(vectorReady).isEqualTo(0);
    }

    @Test
    void snapshotIsolationKeepsPinnedKnowledgeStableAcrossPublications() {
        var organizationId = identityProvider.currentIdentity().organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);
        var clauseId = corpus.semantics().clauses().stream()
                .filter(clause -> clause.primaryConcept().isPresent()
                        && clause.primaryConcept().get() == LegalConcept.LIMITATION_OF_LIABILITY)
                .findFirst().orElseThrow().id();

        var latestRequest = EvidenceRequest.forClauseReview(
                organizationId, LegalConcept.LIMITATION_OF_LIABILITY,
                corpus.contract().contractId(), 1, clauseId,
                M6EvidenceCorpus.SUBJECT_CLAUSE_TEXT, M6EvidenceCorpus.msaProfile(),
                LocalDate.of(2026, 9, 24), KnowledgePin.at(corpus.latestPublicationNumber()));
        var latestBundle = evidenceService.build(latestRequest);
        assertThat(latestBundle.policyItems()).hasSize(1);
        assertThat(latestBundle.policyItems().get(0).text())
                .doesNotContain("2025 policy standard");

        var pinnedRequest = EvidenceRequest.forClauseReview(
                organizationId, LegalConcept.LIMITATION_OF_LIABILITY,
                corpus.contract().contractId(), 1, clauseId,
                M6EvidenceCorpus.SUBJECT_CLAUSE_TEXT, M6EvidenceCorpus.msaProfile(),
                LocalDate.of(2026, 9, 24), KnowledgePin.at(1));
        var pinnedBundle = evidenceService.build(pinnedRequest);
        // at pin 1 the current policy does not exist yet; the superseded 2025 rule is
        // the applicable policy and the library (published later) is honestly missing
        assertThat(pinnedBundle.policyItems()).hasSize(1);
        assertThat(pinnedBundle.policyItems().get(0).text())
                .contains("2025 policy standard");
        assertThat(pinnedBundle.sufficiency()).isEqualTo(EvidenceSufficiency.DRAFTING_MISSING);
    }
}
