package com.rivell.workbench.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rivell.workbench.contracts.ContractEnrichmentService;
import com.rivell.workbench.contracts.ContractIngestionService;
import com.rivell.workbench.contracts.ContractType;
import com.rivell.workbench.contracts.Jurisdiction;
import com.rivell.workbench.contracts.PaperSource;
import com.rivell.workbench.contracts.PartyRole;
import com.rivell.workbench.contracts.ReviewProfile;
import com.rivell.workbench.organization.UserIdentity;
import com.rivell.workbench.retrieval.M6EvidenceCorpus;

/**
 * Engine v2 P2: a review frozen on engine v2 sends a judged position Jev is unsure about to a
 * second opinion. Agreement keeps Jev's finding and says who decided; a disagreement, a failure,
 * an invalid answer or an exhausted budget becomes a LOW "needs your look" finding with both views.
 * The engine frozen in the snapshot decides, never the live setting.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import({LiabilityReviewIT.FakeIntelligenceConfiguration.class,
        LiabilityReviewIT.CorpusConfiguration.class,
        LiabilityReviewIT.MutableIdentityConfiguration.class})
class SecondOpinionIT {

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
        registry.add("rivell.review.engine", () -> "v2");
        // the liability clause has two positions: two second opinions fit, a third does not
        registry.add("rivell.review.routing.max-adjudications", () -> "2");
        registry.add("rivell.storage.local.root",
                () -> "target/test-documents/" + UUID.randomUUID());
    }

    /** Jev flags the clause without conviction: flag mass 0.68, inside the doubt band. */
    private static final Map<String, Double> UNSURE_FLAG = Map.of(
            PolicyComparisonSpecs.MEETS_PREFERRED, 0.30,
            PolicyComparisonSpecs.WITHIN_FALLBACK, 0.02,
            PolicyComparisonSpecs.BEYOND_FALLBACK, 0.62,
            PolicyComparisonSpecs.WALK_AWAY, 0.02,
            PolicyComparisonSpecs.UNCLEAR, 0.02,
            PolicyComparisonSpecs.NOT_ADDRESSED, 0.02);
    /** Jev leans to "meets" without conviction: flag mass 0.51, inside the band, not flagged. */
    private static final Map<String, Double> UNSURE_PASS = Map.of(
            PolicyComparisonSpecs.MEETS_PREFERRED, 0.45,
            PolicyComparisonSpecs.WITHIN_FALLBACK, 0.02,
            PolicyComparisonSpecs.BEYOND_FALLBACK, 0.45,
            PolicyComparisonSpecs.WALK_AWAY, 0.02,
            PolicyComparisonSpecs.UNCLEAR, 0.02,
            PolicyComparisonSpecs.NOT_ADDRESSED, 0.04);
    private static final String LIABILITY = "Limitation of Liability";

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
    LiabilityReviewIT.ReviewGenerativeProvider generativeProvider;

    @Autowired
    M6EvidenceCorpus corpusBuilder;

    @Autowired
    ContractIngestionService contractIngestionService;

    @Autowired
    ContractEnrichmentService enrichmentService;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @BeforeEach
    void freshOrganization() {
        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));
        scriptedProvider.reset();
        generativeProvider.reset();
    }

    private UUID organizationId() {
        return identityProvider.currentIdentity().organizationId();
    }

    /** pc-contract.docx against the PC playbook: the flow PolicyComparisonFlowIT pins. */
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

    /** Jev answers with these probabilities on the liability clause only (two positions). */
    private void jevUnsureOnLiability(Map<String, Double> probabilities) {
        scriptedProvider.policyComparisonProbabilities = probabilities;
        scriptedProvider.probabilityHeadings.add(LIABILITY);
    }

    private List<Finding> findings(UUID runId) {
        return findingRepository.findFindings(organizationId(), runId);
    }

    private static Finding only(List<Finding> findings, IssueFamily family) {
        var matching = findings.stream().filter(finding -> finding.issueFamily() == family).toList();
        assertThat(matching).as("findings of family %s", family).hasSize(1);
        return matching.getFirst();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> judged(UUID runId) {
        return analysisRepository.findExecutions(organizationId(), runId).stream()
                .filter(execution -> execution.detail().get("positions") instanceof List<?>)
                .flatMap(execution -> ((List<Map<String, Object>>) execution.detail()
                        .get("positions")).stream())
                .toList();
    }

    private List<ReviewCheckExecution> comparisons(UUID runId) {
        return analysisRepository.findExecutions(organizationId(), runId).stream()
                .filter(execution -> execution.detail().containsKey("positions"))
                .toList();
    }

    @Test
    void aConfidentJudgementIsAcceptedWithoutASecondOpinion() throws Exception {
        var reviewId = reviewedContract();

        var run = analysisService.analyze(organizationId(), reviewId);

        assertThat(reviewRepository.findSnapshot(organizationId(), reviewId).orElseThrow()
                .reviewEngine()).isEqualTo(ReviewEngine.V2);
        // Jev was sure everywhere (0.9): the findings engine v1 gives, and no second opinion
        assertThat(generativeProvider.secondOpinionCalls.get()).isZero();
        var all = findings(run.id());
        assertThat(all).extracting(Finding::issueFamily).containsExactlyInAnyOrder(
                IssueFamily.POSITION_WALK_AWAY, IssueFamily.POSITION_ACCEPTABLE_FALLBACK);
        assertThat(all).allSatisfy(finding ->
                assertThat(finding.quality()).containsEntry("route", "JEV"));
        assertThat(judged(run.id())).isNotEmpty().allSatisfy(position ->
                assertThat(position).containsEntry("route", "ACCEPT"));
    }

    @Test
    void aSecondOpinionThatAgreesKeepsJevsFindingAndSaysWhoDecided() throws Exception {
        var reviewId = reviewedContract();
        jevUnsureOnLiability(UNSURE_FLAG);
        generativeProvider.secondOpinionCategory = PolicyComparisonSpecs.BEYOND_FALLBACK;

        var run = analysisService.analyze(organizationId(), reviewId);

        // both liability positions sat in the band: two second opinions, both agreed
        assertThat(generativeProvider.secondOpinionCalls.get()).isEqualTo(2);
        var all = findings(run.id());
        var beyond = only(all, IssueFamily.POSITION_NEEDS_FALLBACK);
        assertThat(beyond.severity()).isEqualTo(FindingSeverity.MEDIUM);
        assertThat(beyond.quality()).containsEntry("route", "SECOND_OPINION")
                .containsEntry("category", PolicyComparisonSpecs.BEYOND_FALLBACK);
        assertThat(all).noneMatch(finding -> finding.issueFamily() == IssueFamily.POSITION_UNCERTAIN);
        // the deviation links the second opinion's generation (spec §8)
        assertThat(analysisRepository.findSignals(organizationId(), run.id()).stream()
                .filter(signal -> signal.signalType() == SignalType.POLICY_DEVIATION)
                .filter(signal -> signal.issueFamily() == IssueFamily.POSITION_NEEDS_FALLBACK)
                .toList())
                .isNotEmpty()
                .allSatisfy(signal -> assertThat(signal.generativeExecutionId()).isNotNull());
        assertThat(judged(run.id())).filteredOn(position -> "ADJUDICATE".equals(position.get("route")))
                .hasSize(2)
                .allSatisfy(position -> assertThat(position)
                        .containsEntry("secondOpinionOutcome", "AGREED")
                        .containsEntry("secondOpinionCategory", PolicyComparisonSpecs.BEYOND_FALLBACK));
    }

    @Test
    void aDisagreementBecomesALowNeedsYourLookFindingWithBothViews() throws Exception {
        var reviewId = reviewedContract();
        jevUnsureOnLiability(UNSURE_FLAG);
        generativeProvider.secondOpinionCategory = PolicyComparisonSpecs.MEETS_PREFERRED;

        var run = analysisService.analyze(organizationId(), reviewId);

        var all = findings(run.id());
        // Jev's doubtful flag is not raised as a MEDIUM finding: the user decides
        assertThat(all).noneMatch(finding -> finding.issueFamily() == IssueFamily.POSITION_NEEDS_FALLBACK);
        var look = only(all, IssueFamily.POSITION_UNCERTAIN);
        assertThat(look.severity()).isEqualTo(FindingSeverity.LOW);
        assertThat(look.observationKind()).isEqualTo("UNCERTAINTY:second_opinion_disagrees");
        assertThat(look.proposedAction()).isEqualTo(ProposedAction.ESCALATE_TO_HUMAN);
        assertThat(look.quality())
                .containsEntry("route", "NEEDS_YOUR_LOOK")
                .containsEntry("jevCategory", PolicyComparisonSpecs.BEYOND_FALLBACK)
                .containsEntry("secondOpinionOutcome", "DISAGREED")
                .containsEntry("secondOpinionCategory", PolicyComparisonSpecs.MEETS_PREFERRED)
                .containsKeys("flagProbability", "probabilities", "secondOpinionQuote",
                        "secondOpinionReason")
                .doesNotContainKey("degradationFlags");
        assertThat(comparisons(run.id())).allSatisfy(execution ->
                assertThat(execution.status()).isEqualTo(CheckExecutionStatus.COMPLETED));
    }

    @Test
    void aClauseJevPassedButTheSecondOpinionFlagsIsShownAsNeedsYourLook() throws Exception {
        var reviewId = reviewedContract();
        jevUnsureOnLiability(UNSURE_PASS);
        generativeProvider.secondOpinionCategory = PolicyComparisonSpecs.BEYOND_FALLBACK;

        var run = analysisService.analyze(organizationId(), reviewId);

        var look = only(findings(run.id()), IssueFamily.POSITION_UNCERTAIN);
        assertThat(look.severity()).isEqualTo(FindingSeverity.LOW);
        assertThat(look.quality())
                .containsEntry("jevCategory", PolicyComparisonSpecs.MEETS_PREFERRED)
                .containsEntry("secondOpinionCategory", PolicyComparisonSpecs.BEYOND_FALLBACK);
    }

    @Test
    void aSecondOpinionThatFailsRoutesToYouNeverASilentPass() throws Exception {
        var reviewId = reviewedContract();
        jevUnsureOnLiability(UNSURE_FLAG);
        generativeProvider.failSecondOpinion = true;

        var run = analysisService.analyze(organizationId(), reviewId);

        var all = findings(run.id());
        assertThat(all).noneMatch(finding -> finding.issueFamily() == IssueFamily.POSITION_NEEDS_FALLBACK);
        var look = only(all, IssueFamily.POSITION_UNCERTAIN);
        assertThat(look.severity()).isEqualTo(FindingSeverity.LOW);
        assertThat(look.observationKind()).isEqualTo("UNCERTAINTY:second_opinion_unavailable");
        assertThat(look.quality())
                .containsEntry("secondOpinionOutcome", "UNAVAILABLE")
                .containsEntry("jevCategory", PolicyComparisonSpecs.BEYOND_FALLBACK)
                .containsEntry("degradationFlags", List.of("second_opinion_unavailable"));
        assertThat(comparisons(run.id())).allSatisfy(execution ->
                assertThat(execution.status()).isEqualTo(CheckExecutionStatus.COMPLETED));
    }

    @Test
    void anAnswerOutsideTheOfferedOptionsIsInvalidAndRoutesToYou() throws Exception {
        var reviewId = reviewedContract();
        jevUnsureOnLiability(UNSURE_FLAG);
        // Jev gave walk-away 0.02: not a plausible option, so it is not offered
        generativeProvider.secondOpinionCategory = PolicyComparisonSpecs.WALK_AWAY;

        var run = analysisService.analyze(organizationId(), reviewId);

        var look = only(findings(run.id()), IssueFamily.POSITION_UNCERTAIN);
        assertThat(look.observationKind()).isEqualTo("UNCERTAINTY:second_opinion_unavailable");
        assertThat(look.quality()).containsEntry("secondOpinionOutcome", "INVALID");
        // Jev's doubtful flag is not raised either: the user decides
        assertThat(findings(run.id())).noneMatch(finding ->
                finding.issueFamily() == IssueFamily.POSITION_NEEDS_FALLBACK);
    }

    @Test
    void aQuoteTheClauseDoesNotHoldIsInvalidAndRoutesToYou() throws Exception {
        var reviewId = reviewedContract();
        jevUnsureOnLiability(UNSURE_FLAG);
        generativeProvider.secondOpinionCategory = PolicyComparisonSpecs.BEYOND_FALLBACK;
        generativeProvider.secondOpinionQuote = "The parties shall meet on the moon every Tuesday";

        var run = analysisService.analyze(organizationId(), reviewId);

        var all = findings(run.id());
        assertThat(all).noneMatch(finding -> finding.issueFamily() == IssueFamily.POSITION_NEEDS_FALLBACK);
        assertThat(only(all, IssueFamily.POSITION_UNCERTAIN).quality())
                .containsEntry("secondOpinionOutcome", "INVALID");
    }

    @Test
    void overTheBudgetAPositionRoutesToYouWithoutACall() throws Exception {
        var reviewId = reviewedContract();
        // Jev is unsure on every compared clause: more doubtful positions than the budget of two
        scriptedProvider.policyComparisonProbabilities = UNSURE_FLAG;
        generativeProvider.secondOpinionCategory = PolicyComparisonSpecs.BEYOND_FALLBACK;

        var run = analysisService.analyze(organizationId(), reviewId);

        assertThat(generativeProvider.secondOpinionCalls.get()).isEqualTo(2);
        var outcomes = judged(run.id()).stream()
                .map(position -> position.get("secondOpinionOutcome")).toList();
        assertThat(outcomes).filteredOn("AGREED"::equals).hasSize(2);
        assertThat(outcomes).filteredOn("OVER_BUDGET"::equals).isNotEmpty();
        assertThat(findings(run.id())).anySatisfy(finding -> assertThat(finding.quality())
                .containsEntry("secondOpinionOutcome", "OVER_BUDGET")
                .containsEntry("degradationFlags", List.of("second_opinion_unavailable")));
    }

    @Test
    void theEngineFrozenInTheSnapshotDecidesNotTheLiveSetting() throws Exception {
        var reviewId = reviewedContract();
        // a review created before the switch to v2: its snapshot says v1
        jdbcClient.sql("update review_snapshot set review_engine = 'V1' where review_id = :id")
                .param("id", reviewId).update();
        jevUnsureOnLiability(UNSURE_FLAG);

        var run = analysisService.analyze(organizationId(), reviewId);

        assertThat(generativeProvider.secondOpinionCalls.get()).isZero();
        assertThat(only(findings(run.id()), IssueFamily.POSITION_NEEDS_FALLBACK).quality())
                .containsEntry("route", "JEV");
        assertThat(judged(run.id())).allSatisfy(position ->
                assertThat(position).doesNotContainKey("route"));
    }
}
