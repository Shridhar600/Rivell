package com.rivell.workbench.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.rivell.workbench.intelligence.EmbeddingBatch;
import com.rivell.workbench.intelligence.EmbeddingProvider;
import com.rivell.workbench.intelligence.EmbeddingRequest;
import com.rivell.workbench.knowledge.CandidateType;
import com.rivell.workbench.knowledge.KnowledgeCandidate;
import com.rivell.workbench.organization.UserIdentity;
import com.rivell.workbench.taxonomy.ConceptKey;

/**
 * Engine v2 position search (spec §6, SEARCH route): a clause no topic paired is matched
 * to published playbook positions by its text, full-text and vector lanes fused (RRF),
 * inside the same metadata narrowing (organization, scope, effective window) as every
 * other policy lookup. The caller judges what is found; the search only ranks.
 */
@SpringBootTest
@Testcontainers
@Import({RetrievalIndexReconciliationIT.MutableIdentityConfiguration.class,
        RetrievalIndexReconciliationIT.CorpusConfiguration.class})
class PositionSearchIT {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("pgvector/pgvector:0.8.6-pg17-trixie")
            .asCompatibleSubstituteFor("postgres");

    /** A termination-for-convenience clause in other words than the playbook's. */
    private static final String NOTICE_CLAUSE = "Either party may end this agreement for "
            + "convenience by giving sixty days written notice to the other party.";

    /** A clause about the liability cap in the liability position's own words. */
    private static final String LIABILITY_CLAUSE = "The total liability of Supplier under this "
            + "agreement is uncapped and unlimited, however arising.";

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

    /**
     * Controlled geometry: texts about ending the agreement for convenience share one
     * direction, so the vector lane agrees with the full-text lane on the termination
     * position; everything else falls through to the shared fake. {@code down} simulates
     * a provider outage at query time.
     */
    static final class ControlledEmbeddings implements EmbeddingProvider {

        private final FakeEmbeddingProvider fallback = new FakeEmbeddingProvider();
        volatile boolean down;

        @Override
        public String id() {
            return FakeEmbeddingProvider.PROVIDER_ID;
        }

        @Override
        public EmbeddingBatch embed(EmbeddingRequest request) {
            if (down) {
                throw new com.rivell.workbench.intelligence.ProviderUnavailableException(
                        "embedding provider unavailable");
            }
            var batch = fallback.embed(request);
            var vectors = new java.util.ArrayList<float[]>(batch.vectors());
            for (int i = 0; i < request.inputs().size(); i++) {
                if (request.inputs().get(i).toLowerCase(java.util.Locale.ROOT)
                        .contains("for convenience")) {
                    var direction = new float[FakeEmbeddingProvider.DIMENSIONS];
                    direction[7] = 1f;
                    vectors.set(i, direction);
                }
            }
            return new EmbeddingBatch(vectors, batch.resolvedModel(), batch.usage());
        }
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
        ControlledEmbeddings controlledEmbeddings() {
            return new ControlledEmbeddings();
        }
    }

    @Autowired
    EvidenceApiServiceIT.MutableIdentity identityProvider;

    @Autowired
    M6EvidenceCorpus corpusBuilder;

    @Autowired
    EvidenceService evidenceService;

    @Autowired
    RetrievalRepository repository;

    @Autowired
    ControlledEmbeddings embeddings;

    @Autowired
    org.springframework.jdbc.core.simple.JdbcClient jdbcClient;

    @BeforeEach
    void freshOrganization() {
        embeddings.down = false;
        identityProvider.use(new UserIdentity(UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));
    }

    private UUID organizationId() {
        return identityProvider.currentIdentity().organizationId();
    }

    private List<KnowledgeCandidate> publishPlaybook(
            UUID organizationId, com.rivell.workbench.knowledge.ArtifactManifest manifest) {
        return corpusBuilder.publishAll(organizationId, M6EvidenceCorpus.Fixture.PC_PLAYBOOK,
                manifest, null).stream()
                .filter(candidate -> candidate.candidateType() == CandidateType.PLAYBOOK_RULE)
                .toList();
    }

    private static UUID positionId(List<KnowledgeCandidate> positions, String rawLabel) {
        return positions.stream().filter(candidate -> rawLabel.equals(candidate.rawLabel()))
                .findFirst().orElseThrow().id();
    }

    /** A clause with no topic: the request concept is the placeholder, never used to retrieve. */
    private EvidenceRequest noTopicRequest(UUID organizationId, String clauseText) {
        return EvidenceRequest.forClauseReview(organizationId, ConceptKey.of("GENERAL"),
                UUID.randomUUID(), 1, UUID.randomUUID(), clauseText, M6EvidenceCorpus.msaProfile(),
                LocalDate.of(2026, 9, 24), KnowledgePin.LATEST);
    }

    private static List<UUID> foundIds(EvidenceBundle bundle) {
        return bundle.policyItems().stream().map(EvidenceItem::sourceCandidateId).toList();
    }

    @Test
    void aClauseFindsThePositionThatSpeaksToItsText() {
        var organizationId = organizationId();
        var positions = publishPlaybook(organizationId, M6EvidenceCorpus.msaManifest2026());

        var bundle = evidenceService.buildForPositionSearch(
                noTopicRequest(organizationId, NOTICE_CLAUSE), Set.of(), Set.of(), 3);

        assertThat(foundIds(bundle)).isNotEmpty().hasSizeLessThanOrEqualTo(3);
        assertThat(foundIds(bundle).getFirst()).isEqualTo(positionId(positions, "Termination"));
        // search finds positions only: no drafting, no precedents
        assertThat(bundle.approvedDraftingItems()).isEmpty();
        assertThat(bundle.precedentItems()).isEmpty();
        var trace = repository.findTrace(organizationId, bundle.traceId()).orElseThrow();
        assertThat(trace.stages()).containsKeys("structured", "lexical", "vector", "fusion");
        assertThat(trace.selectedEvidenceIds()).isNotEmpty();
    }

    @Test
    void positionsAlreadyJudgedAreNotReturned() {
        var organizationId = organizationId();
        var positions = publishPlaybook(organizationId, M6EvidenceCorpus.msaManifest2026());
        var termination = positionId(positions, "Termination");

        var bundle = evidenceService.buildForPositionSearch(
                noTopicRequest(organizationId, NOTICE_CLAUSE), Set.of(termination), Set.of(), 3);

        // excluded before ranking: the other three positions still fill the limit
        assertThat(foundIds(bundle)).hasSize(3).doesNotContain(termination);
    }

    @Test
    void positionsCarryingATopicTheCallerExcludedAreNotReturned() {
        var organizationId = organizationId();
        var positions = publishPlaybook(organizationId, M6EvidenceCorpus.msaManifest2026());
        var liability = positions.stream()
                .filter(position -> "Limitation of Liability".equals(position.rawLabel()))
                .findFirst().orElseThrow();
        assertThat(liability.topicKeys()).as("the fixture position must carry a topic").isNotEmpty();

        var bundle = evidenceService.buildForPositionSearch(
                noTopicRequest(organizationId, LIABILITY_CLAUSE), Set.of(),
                Set.copyOf(liability.topicKeys()), 10);

        // excluding the topic a person removed takes its positions out of the candidates
        assertThat(foundIds(bundle)).isNotEmpty().doesNotContain(liability.id());
    }

    @Test
    void whenEmbeddingFailsTheSearchIsFullTextOnly() {
        var organizationId = organizationId();
        var positions = publishPlaybook(organizationId, M6EvidenceCorpus.msaManifest2026());
        embeddings.down = true;

        var bundle = evidenceService.buildForPositionSearch(
                noTopicRequest(organizationId, NOTICE_CLAUSE), Set.of(), Set.of(), 3);

        assertThat(foundIds(bundle).getFirst()).isEqualTo(positionId(positions, "Termination"));
        assertThat(bundle.degradationWarnings()).contains("VECTOR_LANE_UNAVAILABLE");
        assertThat(repository.findTrace(organizationId, bundle.traceId()).orElseThrow()
                .degradation()).contains("VECTOR_LANE_UNAVAILABLE");
        var lanes = bundle.policyItems().getFirst().laneInfo();
        assertThat(lanes.lexicalRank()).isEqualTo(1);
        assertThat(lanes.vectorRank()).isNull();
    }

    @Test
    void positionsTheVectorLaneCannotSeeAreReported() {
        var organizationId = organizationId();
        publishPlaybook(organizationId, M6EvidenceCorpus.msaManifest2026());
        // the embedding provider was down at publication: rows exist, embeddings do not
        jdbcClient.sql("update retrieval_evidence_index set embedding_index_ready = false "
                        + "where organization_id = ?")
                .param(organizationId).update();

        var bundle = evidenceService.buildForPositionSearch(
                noTopicRequest(organizationId, NOTICE_CLAUSE), Set.of(), Set.of(), 3);

        assertThat(bundle.degradationWarnings()).contains("VECTOR_INDEX_INCOMPLETE");
        assertThat(bundle.policyItems()).isNotEmpty().allSatisfy(item ->
                assertThat(item.laneInfo().vectorRank()).isNull());
    }

    @Test
    void scopeRulesStillApply() {
        var organizationId = organizationId();
        var msaPositions = publishPlaybook(organizationId, M6EvidenceCorpus.msaManifest2026());
        // the same playbook text, published for NDAs only: as good a text match, wrong scope
        var ndaPositions = publishPlaybook(organizationId, M6EvidenceCorpus.ndaManifest());

        var bundle = evidenceService.buildForPositionSearch(
                noTopicRequest(organizationId, NOTICE_CLAUSE), Set.of(), Set.of(), 10);

        assertThat(foundIds(bundle)).contains(positionId(msaPositions, "Termination"))
                .doesNotContainAnyElementsOf(
                        ndaPositions.stream().map(KnowledgeCandidate::id).toList());
    }

    @Test
    void anotherOrganizationsPositionsAreNeverFound() {
        var organizationA = organizationId();
        var positionsA = publishPlaybook(organizationA, M6EvidenceCorpus.msaManifest2026());
        identityProvider.use(new UserIdentity(UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));
        var organizationB = organizationId();
        var positionsB = publishPlaybook(organizationB, M6EvidenceCorpus.msaManifest2026());
        identityProvider.use(new UserIdentity(UUID.randomUUID(), organizationA, Set.of("ADMIN")));

        var bundle = evidenceService.buildForPositionSearch(
                noTopicRequest(organizationA, NOTICE_CLAUSE), Set.of(), Set.of(), 10);

        assertThat(foundIds(bundle)).isNotEmpty()
                .allMatch(id -> positionsA.stream().anyMatch(position -> position.id().equals(id)))
                .doesNotContainAnyElementsOf(
                        positionsB.stream().map(KnowledgeCandidate::id).toList());
    }

    @Test
    void searchFoundItemsCarryTheirLaneRanks() {
        var organizationId = organizationId();
        publishPlaybook(organizationId, M6EvidenceCorpus.msaManifest2026());

        var bundle = evidenceService.buildForPositionSearch(
                noTopicRequest(organizationId, NOTICE_CLAUSE), Set.of(), Set.of(), 3);

        var first = bundle.policyItems().getFirst();
        assertThat(first.retrievalReason()).isEqualTo("SEARCH");
        assertThat(first.authorityClass()).isEqualTo(AuthorityClass.POLICY);
        assertThat(first.policyFacts()).isNotNull();
        assertThat(first.laneInfo().lexicalRank()).isEqualTo(1);
        assertThat(first.laneInfo().vectorRank()).isEqualTo(1);
        assertThat(first.laneInfo().fusedRank()).isEqualTo(1);
        assertThat(first.laneInfo().fusedScore()).isPositive();
    }
}
