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
    static class FakeEmbeddingConfiguration {

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

    @Autowired
    com.rivell.workbench.contracts.ContractIngestionService contractIngestionService;

    @Autowired
    com.rivell.workbench.contracts.ContractEnrichmentService enrichmentService;

    @BeforeEach
    void freshOrganization() {
        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), java.util.Set.of("ADMIN")));
    }

    private EvidenceRequest subjectRequest(M6EvidenceCorpus.Corpus corpus) {
        var organizationId = identityProvider.currentIdentity().organizationId();
        var clauseId = corpus.semantics().clauses().stream()
                .filter(clause -> clause.primaryConcept().isPresent()
                        && clause.primaryConcept().get() == LegalConcept.LIMITATION_OF_LIABILITY)
                .findFirst().orElseThrow().id();
        return EvidenceRequest.forClauseReview(
                organizationId, LegalConcept.LIMITATION_OF_LIABILITY,
                corpus.contract().contractId(), 1, clauseId,
                M6EvidenceCorpus.SUBJECT_CLAUSE_TEXT, M6EvidenceCorpus.msaProfile(),
                LocalDate.of(2026, 9, 24), KnowledgePin.at(corpus.latestPublicationNumber()));
    }

    @Test
    void policyAndApprovedDraftingResolveThroughStructuredLookupOnly() {
        var organizationId = identityProvider.currentIdentity().organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);

        var request = subjectRequest(corpus);

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

        // every indexed row carries the fake provider's embedding (deterministic
        // geometry through real pgvector persistence)
        var vectorReady = jdbcClient.sql(
                        "select count(*) from retrieval_evidence_index"
                                + " where organization_id = ? and embedding_index_ready")
                .param(organizationId)
                .query(Integer.class)
                .list().get(0);
        assertThat(vectorReady).isEqualTo(indexedRows);
    }

    private com.rivell.workbench.contracts.ContractIngestionResult ingestAndEnrichMsa() {
        try {
            var bytes = getClass().getClassLoader()
                    .getResourceAsStream("fixtures/m5-msa-services.docx").readAllBytes();
            var ingested = contractIngestionService.ingest("m5-msa-services.docx",
                    com.rivell.workbench.contracts.ContractIngestionService.DOCX_CONTENT_TYPE,
                    new java.io.ByteArrayInputStream(bytes));
            var semantics = enrichmentService.enrich(ingested.contractId(), 1,
                    M6EvidenceCorpus.msaProfile());
            if (semantics.enrichmentStatus()
                    != com.rivell.workbench.contracts.EnrichmentStatus.READY) {
                throw new IllegalStateException("MSA fixture did not enrich READY");
            }
            return ingested;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private java.util.UUID contractClauseId(
            com.rivell.workbench.contracts.ContractIngestionResult contract) {
        var semantics = enrichmentService.getSemantics(contract.contractId(), 1);
        return semantics.clauses().stream()
                .filter(clause -> clause.primaryConcept().isPresent()
                        && clause.primaryConcept().get() == LegalConcept.LIMITATION_OF_LIABILITY)
                .findFirst().orElseThrow().id();
    }

    @Test
    void hybridPrecedentDiscoveryCombinesLexicalAndVectorLanesDeterministically() {
        var organizationId = identityProvider.currentIdentity().organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);
        var bundle = evidenceService.build(subjectRequest(corpus));

        var precedents = bundle.precedentItems();
        assertThat(precedents).isNotEmpty();

        // the strongly relevant precedent tops the fusion: it is present in both
        // lanes near the top, and the fusion is deterministic
        var top = precedents.get(0);
        assertThat(top.text()).contains("The total liability of Supplier shall not exceed");
        assertThat(top.laneInfo().fusedRank()).isEqualTo(1);
        assertThat(top.laneInfo().vectorRank()).isEqualTo(1);
        assertThat(top.authorityClass()).isEqualTo(AuthorityClass.PRECEDENT);

        // vector-strong: the paraphrase ranks high in the vector lane even though its
        // lexical overlap is weak (only the shared actor word)
        var paraphrase = precedents.stream()
                .filter(item -> item.text().contains("overall exposure for damages"))
                .findFirst().orElseThrow();
        assertThat(paraphrase.laneInfo().vectorRank()).isNotNull();
        assertThat(paraphrase.laneInfo().vectorRank())
                .isLessThan(paraphrase.laneInfo().lexicalRank());

        // lexical-first: the exact-terminology fixture tops the lexical lane even though
        // its vector geometry is deliberately unrelated
        var lexicalOnly = precedents.stream()
                .filter(item -> item.text().contains("as measured at the time of the claim"))
                .findFirst().orElseThrow();
        assertThat(lexicalOnly.laneInfo().lexicalRank()).isEqualTo(1);
        assertThat(lexicalOnly.laneInfo().vectorRank())
                .isGreaterThan(paraphrase.laneInfo().vectorRank());

        // bounded reranking executed through the versioned spec with honest judgments
        assertThat(top.semanticAssessment()).isNotNull();
        assertThat(top.semanticAssessment().decisionSpecId())
                .isEqualTo("precedent-relevance");

        // all precedent items carry canonical provenance anchors
        for (var item : precedents) {
            assertThat(item.provenanceNodeIds()).isNotEmpty();
        }
    }

    @Test
    void policyIncompatiblePrecedentStaysEvidenceAndIsFlaggedHonestly() {
        var organizationId = identityProvider.currentIdentity().organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);
        var bundle = evidenceService.build(subjectRequest(corpus));

        var incompatible = bundle.precedentItems().stream()
                .filter(item -> item.text().contains("uncapped and unlimited"))
                .findFirst().orElseThrow();
        // discovered by the lanes, flagged by compatibility — never promoted to policy
        assertThat(incompatible.authorityClass()).isEqualTo(AuthorityClass.PRECEDENT);
        assertThat(incompatible.policyCompatibility()).isEqualTo(PolicyCompatibility.INCOMPATIBLE);
        // policy items still resolve only from the published playbook rule
        assertThat(bundle.policyItems()).hasSize(1);
        assertThat(bundle.policyItems().get(0).authorityClass()).isEqualTo(AuthorityClass.POLICY);
    }

    @Test
    void missingPolicyKeepsPrecedentEvidenceWithoutPromotingItToPolicy() {
        var organizationId = identityProvider.currentIdentity().organizationId();
        // fresh organization: publish only precedent knowledge, no playbook
        corpusBuilder.publishAll(organizationId, M6EvidenceCorpus.Fixture.PRECEDENT_RELEVANT,
                M6EvidenceCorpus.MSA_US_CUSTOMER, null);
        var contract = ingestAndEnrichMsa();
        var request = EvidenceRequest.forClauseReview(
                organizationId, LegalConcept.LIMITATION_OF_LIABILITY,
                contract.contractId(), 1,
                contractClauseId(contract), M6EvidenceCorpus.SUBJECT_CLAUSE_TEXT,
                M6EvidenceCorpus.msaProfile(), LocalDate.of(2026, 9, 24), KnowledgePin.LATEST);
        var bundle = evidenceService.build(request);

        assertThat(bundle.sufficiency()).isEqualTo(EvidenceSufficiency.POLICY_MISSING);
        assertThat(bundle.policyItems()).isEmpty();
        // precedent evidence still exists — it is evidence, never policy
        assertThat(bundle.precedentItems()).isNotEmpty();
        // compatibility is honestly NOT_ASSESSED without applicable policy
        assertThat(bundle.precedentItems())
                .allSatisfy(item -> assertThat(item.policyCompatibility())
                        .isEqualTo(PolicyCompatibility.NOT_ASSESSED));
    }

    @Test
    void ambiguousPolicyIsKeptAmbiguousWithoutSimilarityArbitration() {
        var organizationId = identityProvider.currentIdentity().organizationId();
        corpusBuilder.publishAll(organizationId, M6EvidenceCorpus.Fixture.POLICY_MAIN,
                M6EvidenceCorpus.msaManifest2026(), null);
        corpusBuilder.publishAll(organizationId, M6EvidenceCorpus.Fixture.POLICY_AMBIGUOUS,
                M6EvidenceCorpus.msaManifest2026(), null);
        var contract = ingestAndEnrichMsa();
        var request = EvidenceRequest.forClauseReview(
                organizationId, LegalConcept.LIMITATION_OF_LIABILITY,
                contract.contractId(), 1, contractClauseId(contract),
                M6EvidenceCorpus.SUBJECT_CLAUSE_TEXT, M6EvidenceCorpus.msaProfile(),
                LocalDate.of(2026, 9, 24), KnowledgePin.LATEST);
        var bundle = evidenceService.build(request);

        assertThat(bundle.policyItems()).hasSize(2);
        assertThat(bundle.sufficiency()).isEqualTo(EvidenceSufficiency.AMBIGUOUS_POLICY);
    }

    @Test
    void scopeDimensionsIsolateAuthoritativeEvidence() {
        var organizationId = identityProvider.currentIdentity().organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);
        // additional out-of-scope fixtures in the same org: UK policy, UK library,
        // and a UK-manifested precedent of the same concept
        corpusBuilder.publishAll(organizationId, M6EvidenceCorpus.Fixture.POLICY_UK,
                M6EvidenceCorpus.ukManifest(), null);
        corpusBuilder.publishAll(organizationId, M6EvidenceCorpus.Fixture.LIBRARY_UK,
                M6EvidenceCorpus.ukManifest(), null);
        corpusBuilder.publishAll(organizationId, M6EvidenceCorpus.Fixture.PRECEDENT_LEXICAL,
                M6EvidenceCorpus.ukManifest(), null);

        var bundle = evidenceService.build(subjectRequest(corpus));
        var jurisdictionUS = M6EvidenceCorpus.msaProfile().jurisdiction().name();

        assertThat(bundle.policyItems()).hasSize(1);
        assertThat(bundle.approvedDraftingItems())
                .allSatisfy(item -> assertThat(item.applicabilityScope().jurisdiction())
                        .isEqualToIgnoringCase("US"));
        assertThat(bundle.precedentItems())
                .allSatisfy(item -> assertThat(item.applicabilityScope().jurisdiction())
                        .isEqualToIgnoringCase("US"));
        assertThat(bundle.sufficiency()).isEqualTo(EvidenceSufficiency.SUFFICIENT);
    }

    @Test
    void tenantIsolationBlocksCrossOrganizationEvidence() {
        var organizationA = identityProvider.currentIdentity().organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationA);

        // identity switch: organization B must not read organization A evidence
        identityProvider.use(new UserIdentity(UUID.randomUUID(), UUID.randomUUID(),
                java.util.Set.of("ADMIN")));
        var organizationB = identityProvider.currentIdentity().organizationId();

        var request = EvidenceRequest.forClauseReview(
                organizationA, LegalConcept.LIMITATION_OF_LIABILITY,
                corpus.contract().contractId(), 1,
                corpus.semantics().clauses().stream()
                        .filter(clause -> clause.primaryConcept().isPresent()
                                && clause.primaryConcept().get()
                                        == LegalConcept.LIMITATION_OF_LIABILITY)
                        .findFirst().orElseThrow().id(),
                M6EvidenceCorpus.SUBJECT_CLAUSE_TEXT, M6EvidenceCorpus.msaProfile(),
                LocalDate.of(2026, 9, 24), KnowledgePin.LATEST);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> evidenceService.build(request))
                .isInstanceOf(UnauthorizedEvidenceAccessException.class);

        // org B sees none of org A's knowledge or index state
        var orgBBundle = evidenceService.build(EvidenceRequest.forClauseReview(
                organizationB, LegalConcept.LIMITATION_OF_LIABILITY,
                UUID.randomUUID(), 1, UUID.randomUUID(),
                M6EvidenceCorpus.SUBJECT_CLAUSE_TEXT, M6EvidenceCorpus.msaProfile(),
                LocalDate.of(2026, 9, 24), KnowledgePin.LATEST));
        assertThat(orgBBundle.policyItems()).isEmpty();
        assertThat(orgBBundle.precedentItems()).isEmpty();
        assertThat(orgBBundle.sufficiency()).isEqualTo(EvidenceSufficiency.POLICY_MISSING);
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
