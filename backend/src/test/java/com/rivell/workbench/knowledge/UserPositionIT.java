package com.rivell.workbench.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rivell.workbench.organization.UserIdentity;

/**
 * P2b, knowledge side (design 2026-10-12 §9): a part of an uploaded playbook that Rivell did not
 * take as a position can be made one by a person. Rivell's own reading stays beside the person's,
 * the new candidate goes through review and publication like any other, and the same part can
 * only be made a position once.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import({KnowledgeRecordShapingIT.CorpusIntelligenceConfiguration.class,
        KnowledgeRecordShapingIT.MutableIdentityConfiguration.class})
class UserPositionIT {

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
    }

    @Autowired
    KnowledgeIngestionService ingestionService;

    @Autowired
    KnowledgeQueryService queryService;

    @Autowired
    CandidateReviewService reviewService;

    @Autowired
    KnowledgePublicationService publicationService;

    @Autowired
    UserPositionService positionService;

    @Autowired
    KnowledgeRecordShapingIT.CorpusDecisionProvider provider;

    @Autowired
    KnowledgeRecordShapingIT.MutableIdentityProvider identityProvider;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @BeforeEach
    void freshOrganization() {
        provider.reset();
        identityProvider.use(new UserIdentity(UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));
    }

    private KnowledgeArtifact ingest(ArtifactType type) throws Exception {
        var manifest = new ArtifactManifest(
                Set.of("MSA"), "MSA_PACK", "Customer", "Third-Party", "US", "en", null, null, null);
        try (InputStream stream = getClass().getClassLoader()
                .getResourceAsStream("fixtures/blind-a-negotiation-playbook.docx")) {
            return ingestionService.ingest("a-negotiation-playbook.docx",
                    "application/octet-stream", type, manifest, stream);
        }
    }

    private ResultActions makePosition(UUID artifactId, UUID unitId) throws Exception {
        return mockMvc.perform(post("/api/knowledge/artifacts/{a}/units/{u}/position",
                artifactId, unitId));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString())
                .path("data");
    }

    private static boolean hasText(ExtractionUnit unit) {
        return unit.contentText() != null && !unit.contentText().isBlank();
    }

    /** A part with text that Rivell read as guidance, so it holds no candidate. */
    private ExtractionUnit guidancePart(KnowledgeArtifact artifact) {
        var compiled = queryService.getCandidates(artifact.id()).stream()
                .map(KnowledgeCandidate::extractionUnitId).toList();
        return queryService.getUnits(artifact.id()).stream()
                .filter(unit -> unit.recordKind() == KnowledgeRecordKind.GUIDANCE)
                .filter(UserPositionIT::hasText)
                .filter(unit -> !compiled.contains(unit.id()))
                .findFirst().orElseThrow();
    }

    private List<KnowledgeCandidate> candidatesOf(KnowledgeArtifact artifact, UUID unitId) {
        return queryService.getCandidates(artifact.id()).stream()
                .filter(candidate -> candidate.extractionUnitId().equals(unitId))
                .toList();
    }

    @Test
    void youMakeAPartRivellReadAsGuidanceIntoAPositionAndPublishIt() throws Exception {
        var artifact = ingest(ArtifactType.PLAYBOOK);
        var guidance = guidancePart(artifact);
        var kindDecisionsBefore = provider.kindDecisions.get();

        var created = data(makePosition(artifact.id(), guidance.id())
                .andExpect(status().isCreated()));

        assertThat(created).hasSize(1);
        assertThat(created.get(0).path("reviewStatus").asText()).isEqualTo("PENDING");
        assertThat(created.get(0).path("extractionUnitId").asText())
                .isEqualTo(guidance.id().toString());
        assertThat(created.get(0).path("correctedFields").isArray())
                .as("the wire view carries the saved-correction marker")
                .isTrue();
        assertThat(provider.kindDecisions.get())
                .as("the person decided the kind: Rivell does not ask again")
                .isEqualTo(kindDecisionsBefore);

        var unit = queryService.getUnits(artifact.id()).stream()
                .filter(each -> each.id().equals(guidance.id())).findFirst().orElseThrow();
        assertThat(unit.recordKind()).as("Rivell's reading stays")
                .isEqualTo(KnowledgeRecordKind.GUIDANCE);
        assertThat(unit.override().kind()).isEqualTo(KnowledgeRecordKind.POSITION);
        assertThat(unit.override().by()).isEqualTo(identityProvider.currentIdentity().userId());
        assertThat(unit.override().at()).isNotNull();
        assertThat(unit.effectiveRecordKind()).isEqualTo(KnowledgeRecordKind.POSITION);

        var candidateId = UUID.fromString(created.get(0).path("id").asText());
        assertThat(candidatesOf(artifact, guidance.id())).singleElement()
                .satisfies(candidate -> assertThat(candidate.contentText())
                        .isEqualTo(guidance.contentText()));
        reviewService.approve(candidateId, "Rivell missed this position.");
        publicationService.publish(List.of(candidateId), Map.of(), "Adds a missed position.");
        assertThat(queryService.getCandidate(candidateId).reviewStatus())
                .isEqualTo(ReviewStatus.PUBLISHED);
    }

    /**
     * DW-088: PostgreSQL timestamptz keeps microseconds. The candidates "make this a position"
     * returns are the ones it wrote, so their timestamps must match a later read of them.
     */
    @Test
    void aMadePositionCarriesTheTimestampPrecisionTheDatabaseKeeps() throws Exception {
        var artifact = ingest(ArtifactType.PLAYBOOK);
        var guidance = guidancePart(artifact);

        var created = positionService.makePosition(artifact.id(), guidance.id());

        assertThat(created).hasSize(1);
        var candidate = created.getFirst();
        assertThat(candidate.createdAt().getNano() % 1000).isZero();
        var readBack = queryService.getCandidate(candidate.id());
        assertThat(readBack.createdAt()).isEqualTo(candidate.createdAt());
    }

    @Test
    void aPartCanBeMadeAPositionOnlyOnce() throws Exception {
        var artifact = ingest(ArtifactType.PLAYBOOK);
        var guidance = guidancePart(artifact);
        var first = makePosition(artifact.id(), guidance.id()).andReturn().getResponse();
        assertThat(first.getStatus()).as(first.getContentAsString()).isEqualTo(201);

        makePosition(artifact.id(), guidance.id()).andExpect(status().isConflict());

        assertThat(candidatesOf(artifact, guidance.id())).hasSize(1);
    }

    @Test
    void aPartThatIsAlreadyAPositionOrHasNoTextOrIsElsewhereIsRefused() throws Exception {
        var artifact = ingest(ArtifactType.PLAYBOOK);
        var candidatesBefore = queryService.getCandidates(artifact.id()).size();
        var absorbed = queryService.getCandidates(artifact.id()).getFirst().extractionUnitId();
        makePosition(artifact.id(), absorbed).andExpect(status().isConflict());

        var empty = queryService.getUnits(artifact.id()).stream()
                .filter(unit -> !hasText(unit)).findFirst();
        if (empty.isPresent()) {
            makePosition(artifact.id(), empty.get().id()).andExpect(status().isConflict());
        }

        var other = ingest(ArtifactType.PLAYBOOK);
        makePosition(other.id(), guidancePart(artifact).id()).andExpect(status().isNotFound());
        makePosition(artifact.id(), UUID.randomUUID()).andExpect(status().isNotFound());

        assertThat(queryService.getCandidates(artifact.id())).hasSize(candidatesBefore);
        assertThat(queryService.getUnits(artifact.id()))
                .allSatisfy(unit -> assertThat(unit.override()).isNull());
    }

    @Test
    void onlyPlaybooksAndClauseLibrariesHaveAPositionGateToCorrect() throws Exception {
        var artifact = ingest(ArtifactType.TEMPLATE);
        var part = queryService.getUnits(artifact.id()).stream()
                .filter(UserPositionIT::hasText)
                .filter(unit -> candidatesOf(artifact, unit.id()).isEmpty())
                .findFirst();
        // other types keep every part with text as a candidate; any part still refuses
        var unitId = part.map(ExtractionUnit::id)
                .orElse(queryService.getUnits(artifact.id()).getFirst().id());

        makePosition(artifact.id(), unitId).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("playbook or a clause library")));
    }

    @Test
    void withoutKnowledgeReviewRightsNothingIsWritten() throws Exception {
        var artifact = ingest(ArtifactType.PLAYBOOK);
        var guidance = guidancePart(artifact);
        var admin = identityProvider.currentIdentity();
        identityProvider.use(new UserIdentity(UUID.randomUUID(), admin.organizationId(), Set.of()));

        makePosition(artifact.id(), guidance.id()).andExpect(status().isForbidden());

        identityProvider.use(admin);
        assertThat(candidatesOf(artifact, guidance.id())).isEmpty();
        assertThat(queryService.getUnits(artifact.id()))
                .allSatisfy(unit -> assertThat(unit.override()).isNull());
    }
}
