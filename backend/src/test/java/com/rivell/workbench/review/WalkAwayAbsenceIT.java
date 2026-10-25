package com.rivell.workbench.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.util.List;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rivell.workbench.contracts.ContractEnrichmentService;
import com.rivell.workbench.contracts.ContractIngestionService;
import com.rivell.workbench.contracts.ContractType;
import com.rivell.workbench.contracts.Jurisdiction;
import com.rivell.workbench.contracts.PaperSource;
import com.rivell.workbench.contracts.PartyRole;
import com.rivell.workbench.contracts.ReviewProfile;
import com.rivell.workbench.knowledge.CandidateType;
import com.rivell.workbench.knowledge.KnowledgeCandidate;
import com.rivell.workbench.organization.UserIdentity;
import com.rivell.workbench.retrieval.M6EvidenceCorpus;

/**
 * R3-002: a walk-away that states the absence of a right ("No termination for convenience")
 * becomes a MEDIUM missing-provision finding when no clause addresses the position. A contract
 * made under a parent gets no check, a walk-away about bad wording gets nothing, and a position
 * a clause addresses gets nothing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import({LiabilityReviewIT.FakeIntelligenceConfiguration.class,
        LiabilityReviewIT.CorpusConfiguration.class,
        LiabilityReviewIT.MutableIdentityConfiguration.class})
class WalkAwayAbsenceIT {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("pgvector/pgvector:0.8.6-pg17-trixie")
            .asCompatibleSubstituteFor("postgres");

    private static final String WALK_AWAY_CHECK = "WALK_AWAY_ABSENCE";
    private static final String TERMINATION = "Termination for convenience";
    private static final String TERMINATION_WALK_AWAY = "No termination for convenience";
    private static final String LIABILITY = "Limitation of Liability";
    private static final String LIABILITY_WALK_AWAY =
            "Uncapped or unlimited liability is never acceptable";
    private static final String MISSING_POSITION = "MISSING_POSITION";

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
    LiabilityReviewIT.MutableIdentity identityProvider;

    @Autowired
    ReviewService reviewService;

    @Autowired
    ReviewAnalysisService analysisService;

    @Autowired
    ReviewAnalysisRepository analysisRepository;

    @Autowired
    ReviewRepository reviewRepository;

    @Autowired
    FindingRepository findingRepository;

    @Autowired
    LiabilityReviewIT.ReviewScriptedProvider scriptedProvider;

    @Autowired
    M6EvidenceCorpus corpusBuilder;

    @Autowired
    ContractIngestionService contractIngestionService;

    @Autowired
    ContractEnrichmentService enrichmentService;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    private List<KnowledgeCandidate> positions;

    @BeforeEach
    void freshOrganization() {
        identityProvider.use(new UserIdentity(UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));
        scriptedProvider.reset();
    }

    private UUID organizationId() {
        return identityProvider.currentIdentity().organizationId();
    }

    private void publishPlaybook() {
        positions = corpusBuilder.publishAll(organizationId(),
                        M6EvidenceCorpus.Fixture.WALK_AWAY_PLAYBOOK,
                        M6EvidenceCorpus.msaManifest2026(), null).stream()
                .filter(candidate -> candidate.candidateType() == CandidateType.PLAYBOOK_RULE)
                .toList();
    }

    private UUID newReview(ContractType type) throws Exception {
        var bytes = Files.readAllBytes(java.nio.file.Path.of(
                "src/test/resources/fixtures/walk-away-contract.docx"));
        var ingested = contractIngestionService.ingest("walk-away-contract.docx",
                ContractIngestionService.DOCX_CONTENT_TYPE, new ByteArrayInputStream(bytes));
        enrichmentService.enrich(ingested.contractId(), 1,
                new ReviewProfile(type, PartyRole.CUSTOMER,
                        PaperSource.THIRD_PARTY, Jurisdiction.US));
        return reviewService.create(ingested.contractId(), 1, null).id();
    }

    private List<Finding> absenceFindings(UUID runId) {
        return findingRepository.findFindings(organizationId(), runId).stream()
                .filter(finding -> MISSING_POSITION.equals(finding.observationKind()))
                .toList();
    }

    private JsonNode json(String url, Object... variables) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get(url, variables))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void aWalkAwayStatingAnAbsentRightBecomesAMediumAbsenceFinding() throws Exception {
        publishPlaybook();
        scriptedProvider.walkAwayAnswers.put(TERMINATION_WALK_AWAY, 0.92);
        scriptedProvider.positionAnswers.put(TERMINATION_WALK_AWAY,
                PolicyComparisonSpecs.NOT_ADDRESSED);
        var termination = positions.stream()
                .filter(position -> TERMINATION.equals(position.rawLabel()))
                .findFirst().orElseThrow();
        var reviewId = newReview(ContractType.MSA);

        var run = analysisService.analyze(organizationId(), reviewId);

        var absence = absenceFindings(run.id());
        assertThat(absence).hasSize(1);
        var finding = absence.getFirst();
        assertThat(finding.issueFamily()).isEqualTo(IssueFamily.MISSING_REQUIRED_PROVISION);
        assertThat(finding.severity()).isEqualTo(FindingSeverity.MEDIUM);
        assertThat(finding.reviewClassification()).isEqualTo(FindingClassification.REVIEW_REQUIRED);
        assertThat(finding.proposedAction()).isEqualTo(ProposedAction.ADD_REQUIRED_PROVISION);
        assertThat(finding.observationText()).isEqualTo(
                "Your playbook's walk-away: No termination for convenience. "
                        + "Rivell did not find Termination for convenience in this contract.");
        assertThat(finding.quality())
                .containsEntry("positionLabel", TERMINATION)
                .containsEntry("absenceProbability", 0.92)
                .containsEntry("route", "JEV");
        assertThat(analysisRepository.findEvidenceItems(organizationId(), run.id(),
                finding.evidenceItemIds()))
                .anySatisfy(item -> assertThat(item.sourceCandidateId()).isEqualTo(termination.id()));

        // one batched Jev question; the walk-away text is the question's subject
        assertThat(scriptedProvider.walkAwayCalls()).isEqualTo(1);
        assertThat(scriptedProvider.walkAwayQuestions())
                .anyMatch(question -> question.contains(
                        "Does this walk-away describe the absence of a right or protection"))
                .anyMatch(question -> question.contains(
                        "The walk-away: " + TERMINATION_WALK_AWAY));

        // the label the eval's absence_matches reads reaches the findings API
        JsonNode summary = null;
        for (var item : json("/api/reviews/{id}/findings?limit=100", reviewId).at("/data/items")) {
            if (MISSING_POSITION.equals(item.path("observationKind").asText())) {
                summary = item;
            }
        }
        assertThat(summary).as("the absence finding must be listed").isNotNull();
        assertThat(summary.path("positionLabel").asText()).isEqualTo(TERMINATION);

        // the coverage list says what the search did with the position, on the run API
        var coverage = json("/api/reviews/{id}", reviewId).at("/data/activeRun/playbookCoverage");
        assertThat(coverage.isArray()).as("playbook coverage is exposed").isTrue();
        JsonNode terminationCoverage = null;
        for (var entry : coverage) {
            if (termination.id().toString().equals(
                    entry.path("positionCandidateId").asText())) {
                terminationCoverage = entry;
            }
        }
        assertThat(terminationCoverage).as("the searched position is in the coverage list")
                .isNotNull();
        assertThat(terminationCoverage.path("state").asText()).isEqualTo("NOT_FOUND");
        assertThat(terminationCoverage.path("reason").isNull()).isTrue();
        assertThat(terminationCoverage.path("findingId").asText())
                .isEqualTo(finding.id().toString());
    }

    @Test
    void aContractMadeUnderAParentTypeGetsNoAbsenceCheck() throws Exception {
        publishPlaybook();
        scriptedProvider.walkAwayAnswers.put(TERMINATION_WALK_AWAY, 0.92);
        var reviewId = newReview(ContractType.SOW);

        assertThat(reviewRepository.findPlanChecks(organizationId(), reviewId))
                .noneMatch(check -> WALK_AWAY_CHECK.equals(check.checkKey()));

        var run = analysisService.analyze(organizationId(), reviewId);

        assertThat(absenceFindings(run.id())).isEmpty();
        assertThat(scriptedProvider.walkAwayCalls()).isZero();
    }

    @Test
    void aWalkAwayDescribingBadWordingIsNotAnAbsence() throws Exception {
        publishPlaybook();
        scriptedProvider.positionAnswers.put(LIABILITY_WALK_AWAY,
                PolicyComparisonSpecs.NOT_ADDRESSED);
        scriptedProvider.walkAwayAnswers.put(LIABILITY_WALK_AWAY, 0.1);
        var reviewId = newReview(ContractType.MSA);

        var run = analysisService.analyze(organizationId(), reviewId);

        assertThat(absenceFindings(run.id())).isEmpty();
        assertThat(scriptedProvider.walkAwayQuestions())
                .anyMatch(question -> question.contains(
                        "The walk-away: " + LIABILITY_WALK_AWAY));
    }

    @Test
    void aPositionAClauseAddressesIsNeverAnAbsence() throws Exception {
        publishPlaybook();
        scriptedProvider.positionAnswers.put(LIABILITY_WALK_AWAY,
                PolicyComparisonSpecs.MEETS_PREFERRED);
        scriptedProvider.walkAwayAnswers.put(LIABILITY_WALK_AWAY, 0.92);
        var reviewId = newReview(ContractType.MSA);

        var run = analysisService.analyze(organizationId(), reviewId);

        assertThat(absenceFindings(run.id())).isEmpty();
        assertThat(scriptedProvider.walkAwayQuestions())
                .noneMatch(question -> question.contains(
                        "The walk-away: " + LIABILITY_WALK_AWAY));
    }
}
