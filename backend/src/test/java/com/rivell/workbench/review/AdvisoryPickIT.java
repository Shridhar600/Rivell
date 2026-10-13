package com.rivell.workbench.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

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
 * Engine v2 playbook-silent advisory picks (P4): the whole-contract read runs in every v2 review,
 * and a clause no position addresses, the triage did not flag and the comparison left alone
 * becomes a LOW "needs your look" item; a clause with a finding or a flagged triage keeps it out,
 * and v1 keeps the opt-in MEDIUM behaviour.
 */
@SpringBootTest
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
        registry.add("rivell.review.engine", () -> "v2");
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
    void aPlaybookSilentClauseTheReadPicksIsALowNeedsYourLookItem() throws Exception {
        var reviewId = reviewedContract();
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
    void v1KeepsTheOptInBehaviour() throws Exception {
        var reviewId = reviewedContract();
        // a review frozen on v1: the live v2 setting must not make the read run or report
        jdbcClient.sql("update review_snapshot set review_engine = 'V1' where review_id = :id")
                .param("id", reviewId).update();
        scriptedProvider.headingAnswers.put(GOVERNING_LAW, PolicyComparisonSpecs.NOT_ADDRESSED);
        generativeProvider.spottedHighHeadings.add(GOVERNING_LAW);

        var run = analysisService.analyze(organizationId(), reviewId);

        assertThat(generativeProvider.spottingCalls).as("v1 without the opt-in never reads")
                .hasValue(0);
        assertThat(spottedFindings(findings(run.id()))).isEmpty();
        // the playbook-silent clause was still triaged and stayed below the threshold
        assertThat(analysisRepository.findExecutions(organizationId(), run.id()))
                .anySatisfy(execution -> assertThat(execution.detail())
                        .containsEntry("uncoveredProbability", 0.05));
    }
}
