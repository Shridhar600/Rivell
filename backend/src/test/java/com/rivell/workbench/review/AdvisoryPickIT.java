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
import com.rivell.workbench.contracts.ContractRepository;
import com.rivell.workbench.contracts.ContractType;
import com.rivell.workbench.contracts.DocumentNode;
import com.rivell.workbench.contracts.DocumentNodeType;
import com.rivell.workbench.contracts.Jurisdiction;
import com.rivell.workbench.contracts.PaperSource;
import com.rivell.workbench.contracts.PartyRole;
import com.rivell.workbench.contracts.ReviewProfile;
import com.rivell.workbench.organization.UserIdentity;
import com.rivell.workbench.retrieval.M6EvidenceCorpus;

/**
 * Engine v2 playbook-silent advisory picks (P4): the whole-contract read runs in every v2 review,
 * and a clause no position addresses, the triage did not flag and the comparison left alone
 * becomes a LOW "needs your look" item; a clause with a finding or a flagged triage keeps it out.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import({LiabilityReviewIT.FakeIntelligenceConfiguration.class,
        LiabilityReviewIT.CorpusConfiguration.class,
        LiabilityReviewIT.MutableIdentityConfiguration.class})
class AdvisoryPickIT {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("pgvector/pgvector:0.8.6-pg17-trixie")
            .asCompatibleSubstituteFor("postgres");

    private static final String GOVERNING_LAW = "Governing Law";
    private static final String TERMINATION = "Termination";
    private static final String LIABILITY = "Limitation of Liability";

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
    FindingRepository findingRepository;

    @Autowired
    ReviewRepository reviewRepository;

    @Autowired
    ContractRepository contractRepository;

    @Autowired
    LiabilityReviewIT.ReviewScriptedProvider scriptedProvider;

    @Autowired
    LiabilityReviewIT.ReviewGenerativeProvider generativeProvider;

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

    private JsonNode json(String url, Object... variables) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get(url, variables))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @BeforeEach
    void freshOrganization() {
        identityProvider.use(new UserIdentity(UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));
        scriptedProvider.reset();
        generativeProvider.reset();
    }

    private UUID organizationId() {
        return identityProvider.currentIdentity().organizationId();
    }

    /** pc-contract.docx against the PC playbook (the flow PositionSearchRouteIT pins). */
    private UUID reviewedContract() throws Exception {
        corpusBuilder.publishAll(organizationId(), M6EvidenceCorpus.Fixture.PC_PLAYBOOK,
                M6EvidenceCorpus.msaManifest2026(), null);
        var bytes = Files.readAllBytes(java.nio.file.Path.of(
                "src/test/resources/fixtures/pc-contract.docx"));
        var ingested = contractIngestionService.ingest("pc-contract.docx",
                ContractIngestionService.DOCX_CONTENT_TYPE, new ByteArrayInputStream(bytes));
        enrichmentService.enrich(ingested.contractId(), 1,
                new ReviewProfile(ContractType.MSA, PartyRole.CUSTOMER,
                        PaperSource.THIRD_PARTY, Jurisdiction.US));
        return reviewService.create(ingested.contractId(), 1, null).id();
    }

    private List<Finding> findings(UUID runId) {
        return findingRepository.findFindings(organizationId(), runId);
    }

    private static List<Finding> spottedFindings(List<Finding> findings) {
        return findings.stream()
                .filter(finding -> PolicyUnavailablePayload.SPOTTED.equals(finding.observationKind()))
                .toList();
    }

    /** The check execution whose detail records the read's pick at this severity. */
    private ReviewCheckExecution spottedCheck(UUID runId, String severity) {
        return analysisRepository.findExecutions(organizationId(), runId).stream()
                .filter(execution -> severity.equals(execution.detail().get("spotted")))
                .findFirst().orElseThrow();
    }

    @Test
    void aPlaybookSilentClauseTheReadPicksIsALowNeedsYourLookItem() throws Exception {        var reviewId = reviewedContract();
        // Governing Law is playbook-silent: no position addresses it, the triage finds it normal
        scriptedProvider.headingAnswers.put(GOVERNING_LAW, PolicyComparisonSpecs.NOT_ADDRESSED);
        generativeProvider.spottedHighHeadings.add(GOVERNING_LAW);

        var run = analysisService.analyze(organizationId(), reviewId);

        assertThat(generativeProvider.spottingCalls).as("one read per run").hasValue(1);
        var spotted = spottedFindings(findings(run.id()));
        assertThat(spotted).hasSize(1);
        var advisory = spotted.getFirst();
        assertThat(advisory.severity()).isEqualTo(FindingSeverity.LOW);
        assertThat(advisory.issueFamily()).isEqualTo(IssueFamily.POLICY_UNAVAILABLE);
        assertThat(advisory.quality())
                .containsEntry("route", "NEEDS_YOUR_LOOK")
                .containsEntry("uncertaintyReason", "playbook_silent")
                .containsEntry("spottedSeverity", "HIGH")
                .containsEntry("spottedReason", "one-sided term against us")
                .containsEntry("uncoveredProbability", 0.05);
        // the check detail keeps the read's own severity
        assertThat(spottedCheck(run.id(), "HIGH").detail()).containsEntry("addressed", false);
        // and the diagnostics view exposes the read's severity and the addressed flag
        JsonNode spottedView = null;
        for (var view : json("/api/reviews/{id}/diagnostics", reviewId)
                .at("/data/analysis/checks")) {
            if ("HIGH".equals(view.path("spottedSeverity").asText())) {
                spottedView = view;
            }
        }
        assertThat(spottedView).as("the read's pick must reach diagnostics with its severity")
                .isNotNull();
        assertThat(spottedView.path("addressed").asBoolean()).isFalse();
    }

    @Test
    void aMediumPickUnderAllIsStillALowAdvisoryItem() throws Exception {
        var reviewId = reviewedContract();
        scriptedProvider.headingAnswers.put(GOVERNING_LAW, PolicyComparisonSpecs.NOT_ADDRESSED);
        generativeProvider.spottedHeadings.add(GOVERNING_LAW);

        var run = analysisService.analyze(organizationId(), reviewId);

        var spotted = spottedFindings(findings(run.id()));
        assertThat(spotted).hasSize(1);
        assertThat(spotted.getFirst().severity()).isEqualTo(FindingSeverity.LOW);
        assertThat(spotted.getFirst().quality())
                .containsEntry("route", "NEEDS_YOUR_LOOK")
                .containsEntry("uncertaintyReason", "playbook_silent")
                .containsEntry("spottedSeverity", "MEDIUM");
        assertThat(spottedCheck(run.id(), "MEDIUM").detail()).containsEntry("addressed", false);
    }

    @Test
    void aClauseWithAFindingGetsNoAdvisoryPick() throws Exception {
        var reviewId = reviewedContract();
        // the uncapped liability clause is flagged walk-away by the comparison; the read picks it too
        generativeProvider.spottedHeadings.add(LIABILITY);

        var run = analysisService.analyze(organizationId(), reviewId);

        assertThat(spottedFindings(findings(run.id()))).isEmpty();
        assertThat(findings(run.id())).anySatisfy(finding -> {
            assertThat(finding.issueFamily()).isEqualTo(IssueFamily.POSITION_WALK_AWAY);
            assertThat(finding.severity()).isEqualTo(FindingSeverity.HIGH);
        });
    }

    @Test
    void aClauseTheTriageFlagsGetsNoAdvisoryPick() throws Exception {
        var reviewId = reviewedContract();
        scriptedProvider.headingAnswers.put(GOVERNING_LAW, PolicyComparisonSpecs.NOT_ADDRESSED);
        scriptedProvider.uncoveredProbability = 0.9;
        generativeProvider.spottedHighHeadings.add(GOVERNING_LAW);

        var run = analysisService.analyze(organizationId(), reviewId);

        var notCovered = findings(run.id()).stream()
                .filter(finding -> PolicyUnavailablePayload.NOT_COVERED
                        .equals(finding.observationKind()))
                .toList();
        assertThat(notCovered).hasSize(1);
        assertThat(notCovered.getFirst().severity()).isEqualTo(FindingSeverity.MEDIUM);
        // the triage's finding stands alone: the read's pick must not join it
        assertThat(notCovered.getFirst().signalIds()).hasSize(1);
        assertThat(spottedFindings(findings(run.id()))).isEmpty();
    }

    @Test
    void aSearchFoundButUnaddressedClauseStillReachesTheRead() throws Exception {
        // the topics decision finds no topic in the termination clause; only the search reaches it
        scriptedProvider.topiclessHeadings.add(TERMINATION);
        var reviewId = reviewedContract();
        scriptedProvider.headingAnswers.put(TERMINATION, PolicyComparisonSpecs.NOT_ADDRESSED);
        generativeProvider.spottedHighHeadings.add(TERMINATION);

        var run = analysisService.analyze(organizationId(), reviewId);

        var check = spottedCheck(run.id(), "HIGH");
        assertThat(check.detail()).containsEntry("route", "SEARCH").containsEntry("addressed", false);
        var spotted = spottedFindings(findings(run.id()));
        assertThat(spotted).hasSize(1);
        assertThat(spotted.getFirst().severity()).isEqualTo(FindingSeverity.LOW);
        assertThat(spotted.getFirst().quality())
                .containsEntry("route", "NEEDS_YOUR_LOOK")
                .containsEntry("uncertaintyReason", "playbook_silent")
                .containsEntry("spottedSeverity", "HIGH")
                .containsEntry("uncoveredProbability", 0.05);
    }

    @Test
    void anIssueIsAttachedToTheClauseContainingItsQuote() throws Exception {
        var reviewId = reviewedContract();
        scriptedProvider.headingAnswers.put(GOVERNING_LAW, PolicyComparisonSpecs.NOT_ADDRESSED);
        scriptedProvider.headingAnswers.put(TERMINATION, PolicyComparisonSpecs.NOT_ADDRESSED);
        var review = reviewRepository.find(organizationId(), reviewId).orElseThrow();
        var structure = contractRepository.loadStructure(review.contractVersionId());
        var terminationHeading = heading(structure.nodes(), TERMINATION);
        var governingHeading = heading(structure.nodes(), GOVERNING_LAW);
        // The read names the termination clause but quotes the governing-law clause. The quote
        // decides: the issue belongs to governing law, and termination gets nothing.
        generativeProvider.spottingOutput = state -> {
            var termination = spottingClause(state, TERMINATION);
            var governing = spottingClause(state, GOVERNING_LAW);
            var quote = governing.text().lines().map(String::strip)
                    .filter(line -> !line.isBlank() && !line.equals(governing.heading()))
                    .findFirst().orElseThrow();
            return termination.number() + " | HIGH | " + quote + " | governing law is foreign";
        };

        var run = analysisService.analyze(organizationId(), reviewId);

        var spotted = spottedFindings(findings(run.id()));
        assertThat(spotted).hasSize(1);
        assertThat(spotted.getFirst().subjectHeadingNodeId()).isEqualTo(governingHeading.id());
        assertThat(spotted).noneMatch(
                finding -> terminationHeading.id().equals(finding.subjectHeadingNodeId()));
    }

    @Test
    void twoGroundedIssuesOnOneClauseAreTwoFindings() throws Exception {
        var reviewId = reviewedContract();
        scriptedProvider.headingAnswers.put(GOVERNING_LAW, PolicyComparisonSpecs.NOT_ADDRESSED);
        var review = reviewRepository.find(organizationId(), reviewId).orElseThrow();
        var governingHeading = heading(
                contractRepository.loadStructure(review.contractVersionId()).nodes(), GOVERNING_LAW);
        generativeProvider.spottingOutput = state -> {
            var governing = spottingClause(state, GOVERNING_LAW);
            var lines = governing.text().lines().map(String::strip)
                    .filter(line -> !line.isBlank()).toList();
            return governing.number() + " | HIGH | " + lines.get(0) + " | first issue\n"
                    + governing.number() + " | MEDIUM | " + lines.get(1) + " | second issue";
        };

        var run = analysisService.analyze(organizationId(), reviewId);

        var spotted = spottedFindings(findings(run.id()));
        assertThat(spotted).hasSize(2);
        assertThat(spotted).allSatisfy(finding -> {
            assertThat(finding.subjectHeadingNodeId()).isEqualTo(governingHeading.id());
            assertThat(finding.quality()).containsEntry("route", "NEEDS_YOUR_LOOK");
        });
        assertThat(spotted).extracting(finding -> finding.quality().get("spottedSeverity"))
                .containsExactlyInAnyOrder("HIGH", "MEDIUM");
    }

    @Test
    void anIssueWhoseQuoteIsNotFoundIsDroppedAndCounted() throws Exception {
        var reviewId = reviewedContract();
        scriptedProvider.headingAnswers.put(GOVERNING_LAW, PolicyComparisonSpecs.NOT_ADDRESSED);
        generativeProvider.spottingOutput = state -> spottingClause(state, GOVERNING_LAW).number()
                + " | HIGH | words that appear in no clause | invented issue";

        var run = analysisService.analyze(organizationId(), reviewId);

        assertThat(spottedFindings(findings(run.id()))).isEmpty();
        var execution = analysisRepository.findExecutions(organizationId(), run.id()).stream()
                .filter(candidate -> candidate.detail().containsKey("spottingDropped"))
                .findFirst().orElseThrow();
        assertThat(execution.detail()).containsEntry("spottingDropped", 1);
        // The run-level diagnostics expose the dropped count once, so a quiet read is not
        // mistaken for a clean one and summing per-clause rows cannot overstate it.
        assertThat(json("/api/reviews/{id}/diagnostics", reviewId)
                .at("/data/analysis/spottingDropped").asInt(-1)).isEqualTo(1);
    }

    private static DocumentNode heading(List<DocumentNode> nodes, String text) {
        return nodes.stream()
                .filter(node -> node.nodeType() == DocumentNodeType.HEADING)
                .filter(node -> node.text() != null && node.text().contains(text))
                .findFirst().orElseThrow();
    }

    private static IssueSpottingSpecs.SpottingClause spottingClause(
            IssueSpottingSpecs.SpottingState state, String heading) {
        return state.clauses().stream()
                .filter(clause -> clause.heading().contains(heading))
                .findFirst().orElseThrow();
    }

    @Test
    void aFailedTriageDegradesTheClauseCheckInsteadOfReadingAsClean() throws Exception {
        var reviewId = reviewedContract();
        scriptedProvider.headingAnswers.put(GOVERNING_LAW, PolicyComparisonSpecs.NOT_ADDRESSED);
        scriptedProvider.failUncoveredTriage = true;

        var run = analysisService.analyze(organizationId(), reviewId);

        var execution = analysisRepository.findExecutions(organizationId(), run.id()).stream()
                .filter(candidate -> Boolean.TRUE.equals(
                        candidate.detail().get("uncoveredTriageUnavailable")))
                .findFirst().orElseThrow();
        assertThat(execution.status()).isEqualTo(CheckExecutionStatus.DEGRADED);
        assertThat(execution.detail()).containsEntry(
                "degradedReason", "uncovered_triage_unavailable");
        assertThat(reviewRepository.find(organizationId(), reviewId).orElseThrow().status())
                .isEqualTo(ReviewStatus.COMPLETED);
    }

    @Test
    void outOfCreditOnTheWholeContractReadDegradesWithItsOwnReason() throws Exception {
        var reviewId = reviewedContract();
        scriptedProvider.headingAnswers.put(GOVERNING_LAW, PolicyComparisonSpecs.NOT_ADDRESSED);
        generativeProvider.spottingOutOfCredit = true;

        var run = analysisService.analyze(organizationId(), reviewId);

        assertThat(analysisRepository.findExecutions(organizationId(), run.id()))
                .anySatisfy(execution -> assertThat(execution.detail())
                        .containsEntry("degradedReason", "provider_out_of_credit"));
        assertThat(spottedFindings(findings(run.id()))).isEmpty();
        assertThat(reviewRepository.find(organizationId(), reviewId).orElseThrow().status())
                .isEqualTo(ReviewStatus.COMPLETED);
    }

    @Test
    void aFailedWholeContractReadIsRecordedOnTheClauseCheck() throws Exception {
        var reviewId = reviewedContract();
        scriptedProvider.headingAnswers.put(GOVERNING_LAW, PolicyComparisonSpecs.NOT_ADDRESSED);
        generativeProvider.failSpotting = true;

        var run = analysisService.analyze(organizationId(), reviewId);

        assertThat(analysisRepository.findExecutions(organizationId(), run.id()))
                .anySatisfy(execution -> {
                    assertThat(execution.detail()).containsEntry("addressed", false);
                    assertThat(execution.detail()).containsEntry("spottingUnavailable", true);
                });
        assertThat(spottedFindings(findings(run.id()))).isEmpty();
        assertThat(reviewRepository.find(organizationId(), reviewId).orElseThrow().status())
                .isEqualTo(ReviewStatus.COMPLETED);
    }

    @Test
    void aV2PlanHasNoOmissionCheckWhenThePropertyIsOff() throws Exception {
        var reviewId = reviewedContract();

        var snapshot = reviewRepository.findSnapshot(organizationId(), reviewId).orElseThrow();
        assertThat(snapshot.reviewEngine()).isEqualTo(ReviewEngine.V2);
        // this IT leaves rivell.review.omissions unset: the wired bean must keep that lane off
        // (the walk-away absence check shares the mechanism but has its own key and default)
        assertThat(reviewRepository.findPlanChecks(organizationId(), reviewId))
                .noneMatch(check -> "OMISSION".equals(check.checkKey()));
    }
}
