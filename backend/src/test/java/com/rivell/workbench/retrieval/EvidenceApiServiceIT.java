package com.rivell.workbench.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.rivell.workbench.contracts.EnrichmentStatus;
import com.rivell.workbench.organization.IdentityProvider;
import com.rivell.workbench.organization.UserIdentity;
import com.rivell.workbench.retrieval.api.EvidenceApiService;
import com.rivell.workbench.retrieval.api.RetrievalRequests;
import com.rivell.workbench.retrieval.api.RetrievalResponses;

/**
 * The M6 developer API flow: build evidence from a reviewed clause of the enriched
 * MSA, with honest degradation warnings and no provider internals in the response.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class EvidenceApiServiceIT {

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
    EvidenceApiService evidenceApiService;

    @Autowired
    com.rivell.workbench.contracts.ContractEnrichmentService enrichmentService;

    @Autowired
    com.rivell.workbench.contracts.ContractIngestionService contractIngestionService;

    @Autowired
    MockMvc mockMvc;

    @BeforeEach
    void freshOrganization() {
        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), java.util.Set.of("ADMIN")));
    }

    @Test
    void buildsEvidenceBundleFromTheReviewedClauseWithoutInventedSemantics() {
        var organizationId = identityProvider.currentIdentity().organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);
        var clauseId = corpus.semantics().clauses().stream()
                .filter(clause -> clause.primaryConcept().isPresent()
                        && clause.primaryConcept().get()
                                == com.rivell.workbench.contracts.LegalConcept.LIMITATION_OF_LIABILITY)
                .findFirst().orElseThrow().id();

        var response = evidenceApiService.build(new RetrievalRequests.EvidenceBuildRequest(
                corpus.contract().contractId(), 1, clauseId));

        // the bundle surfaces every authority lane with the human-declared context
        assertThat(response.request().purpose()).isEqualTo("CLAUSE_REVIEW");
        assertThat(response.request().legalConcept())
                .isEqualTo("LIMITATION_OF_LIABILITY");
        assertThat(response.items()).isNotEmpty();
        assertThat(response.items()).anySatisfy(item ->
                assertThat(item.authorityClass()).isEqualTo("POLICY"));
        // G-M6-1: the item carries its own applicable scope (canonical Knowledge
        // ApplicabilityScope semantics) — the published source scope, not recomputed
        assertThat(response.items()).anySatisfy(item -> {
            assertThat(item.authorityClass()).isEqualTo("POLICY");
            assertThat(item.applicabilityScope()).isNotNull();
            assertThat(item.applicabilityScope().contractPackKey())
                    .isEqualToIgnoringCase("MSA_PACK");
            assertThat(item.applicabilityScope().declaredContractTypes())
                    .containsExactly("MSA");
            assertThat(item.applicabilityScope().partyPerspective())
                    .isEqualToIgnoringCase("Customer");
            assertThat(item.applicabilityScope().jurisdiction())
                    .isEqualToIgnoringCase("US");
            assertThat(item.applicabilityScope().effectiveFrom())
                    .isEqualTo(java.time.LocalDate.of(2026, 1, 1));
            assertThat(item.applicabilityScope().effectiveTo())
                    .isEqualTo(java.time.LocalDate.of(2026, 12, 31));
        });
        assertThat(response.items()).anySatisfy(item ->
                assertThat(item.authorityClass()).isEqualTo("PRECEDENT"));
        // precedent items carry the published precedent's scope as retrieved at the pin
        assertThat(response.items()).anySatisfy(item -> {
            assertThat(item.authorityClass()).isEqualTo("PRECEDENT");
            assertThat(item.applicabilityScope()).isNotNull();
            assertThat(item.applicabilityScope().jurisdiction())
                    .isEqualToIgnoringCase("US");
        });
        assertThat(response.sufficiency()).isEqualTo("SUFFICIENT");
        assertThat(response.traceId()).isNotNull();

        // no Finding/severity/violation/recommendation vocabulary in the product payload
        var responseJson = String.valueOf(response);
        for (var banned : new String[] {"finding", "severity", "violation",
                "recommendation", "redline"}) {
            assertThat(responseJson.toLowerCase()).doesNotContain(banned);
        }
    }

    @Test
    void evidenceResolvesTheHumanCorrectedConceptNotTheMachineProposal() {
        var organizationId = identityProvider.currentIdentity().organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);
        var liabilityClauseId = corpus.semantics().clauses().stream()
                .filter(clause -> clause.primaryConcept().isPresent()
                        && clause.primaryConcept().get()
                                == com.rivell.workbench.contracts.LegalConcept
                                        .LIMITATION_OF_LIABILITY)
                .findFirst().orElseThrow().id();
        // human correction: the clause is actually a confidentiality clause
        enrichmentService.correctClause(corpus.contract().contractId(), 1, liabilityClauseId,
                com.rivell.workbench.contracts.FactCorrection.ofPrimary(
                        com.rivell.workbench.contracts.LegalConcept.CONFIDENTIALITY));

        // the corrected concept drives applicability: no CONFIDENTIALITY policy is
        // published in the corpus → POLICY_MISSING is honest, and the discovery lanes
        // surface the corpus's confidentiality-tagged drafting/precedents instead of
        // the LOL evidence the machine concept would wrongly resolve
        var response = evidenceApiService.build(new RetrievalRequests.EvidenceBuildRequest(
                corpus.contract().contractId(), 1, liabilityClauseId));
        assertThat(response.request().legalConcept()).isEqualTo("CONFIDENTIALITY");
        assertThat(response.sufficiency()).isEqualTo("POLICY_MISSING");
        // knowledge-side items must follow the corrected concept; current-contract
        // items legitimately carry their own clause concepts
        assertThat(response.items())
                .filteredOn(item -> item.publicationId() != null)
                .noneSatisfy(item -> assertThat(item.legalConcept())
                        .isEqualTo("LIMITATION_OF_LIABILITY"));
    }

    @Test
    void unknownClauseAndMissingEnrichmentAreHonestErrors() {
        var organizationId = identityProvider.currentIdentity().organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);

        assertThatThrownBy(() -> evidenceApiService.build(
                new RetrievalRequests.EvidenceBuildRequest(
                        corpus.contract().contractId(), 1, UUID.randomUUID())))
                .isInstanceOf(ClauseNotFoundException.class);
    }

    @Test
    void errorResponsesMatchTheDocumentedStatuses() throws Exception {
        var organizationId = identityProvider.currentIdentity().organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);
        var contractId = corpus.contract().contractId();

        mockMvc.perform(post("/api/retrieval/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contractId\":\"" + contractId
                                + "\",\"versionNumber\":1,\"clauseId\":\""
                                + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/retrieval/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contractId\":\"" + UUID.randomUUID()
                                + "\",\"versionNumber\":1,\"clauseId\":\""
                                + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/retrieval/traces/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void ingestedButNeverEnrichedContractIsNotFound() throws Exception {
        var organizationId = identityProvider.currentIdentity().organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);
        var bytes = getClass().getResourceAsStream("/fixtures/m5-msa-services.docx")
                .readAllBytes();
        var ingested = contractIngestionService.ingest(
                "m6-unenriched-" + UUID.randomUUID() + ".docx",
                com.rivell.workbench.contracts.ContractIngestionService.DOCX_CONTENT_TYPE,
                new ByteArrayInputStream(bytes));
        org.assertj.core.api.Assertions.assertThat(
                        corpus.contract().contractId()).isNotEqualTo(ingested.contractId());

        mockMvc.perform(post("/api/retrieval/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contractId\":\"" + ingested.contractId()
                                + "\",\"versionNumber\":1,\"clauseId\":\""
                                + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void crossOrganizationTraceIsNotFound() throws Exception {
        var organizationId = identityProvider.currentIdentity().organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);
        var clauseId = corpus.semantics().clauses().stream()
                .filter(clause -> clause.primaryConcept().isPresent()
                        && clause.primaryConcept().get()
                                == com.rivell.workbench.contracts.LegalConcept
                                        .LIMITATION_OF_LIABILITY)
                .findFirst().orElseThrow().id();
        var response = evidenceApiService.build(new RetrievalRequests.EvidenceBuildRequest(
                corpus.contract().contractId(), 1, clauseId));

        identityProvider.use(new com.rivell.workbench.organization.UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), java.util.Set.of("ADMIN")));

        mockMvc.perform(get("/api/retrieval/traces/" + response.traceId()))
                .andExpect(status().isNotFound());
    }
}
