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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
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
 * Severity R3 levers switched off by their properties: the engine behaves as before P7 (no lever
 * changes a finding, adds a call, or leaves a tag), the same inputs that the lever tests use.
 */
@SpringBootTest
@Testcontainers
@Import({LiabilityReviewIT.FakeIntelligenceConfiguration.class,
        LiabilityReviewIT.CorpusConfiguration.class,
        LiabilityReviewIT.MutableIdentityConfiguration.class})
class SeverityR3LeversOffIT {

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
        registry.add("rivell.review.severity.agreed-worse-category", () -> "false");
        registry.add("rivell.review.routing.within-split-margin", () -> "-1");
        registry.add("rivell.review.fallback-conditions.enabled", () -> "false");
        registry.add("rivell.storage.local.root",
                () -> "target/test-documents/" + UUID.randomUUID());
    }

    /** Jev says WITHIN_FALLBACK without conviction (flag mass 0.67, in the band); BEYOND is plausible. */
    private static final Map<String, Double> UNSURE_WITHIN = Map.of(
            PolicyComparisonSpecs.MEETS_PREFERRED, 0.27,
            PolicyComparisonSpecs.WITHIN_FALLBACK, 0.55,
            PolicyComparisonSpecs.BEYOND_FALLBACK, 0.08,
            PolicyComparisonSpecs.WALK_AWAY, 0.02,
            PolicyComparisonSpecs.UNCLEAR, 0.02,
            PolicyComparisonSpecs.NOT_ADDRESSED, 0.06);
    /** Jev says BEYOND_FALLBACK without conviction (flag mass 0.66, in the band); WITHIN is plausible. */
    private static final Map<String, Double> UNSURE_BEYOND = Map.of(
            PolicyComparisonSpecs.MEETS_PREFERRED, 0.30,
            PolicyComparisonSpecs.WITHIN_FALLBACK, 0.12,
            PolicyComparisonSpecs.BEYOND_FALLBACK, 0.50,
            PolicyComparisonSpecs.WALK_AWAY, 0.02,
            PolicyComparisonSpecs.UNCLEAR, 0.02,
            PolicyComparisonSpecs.NOT_ADDRESSED, 0.04);
    /** Confident WITHIN (flag mass 0.94) whose BEYOND + WALK_AWAY mass is 0.46: P2 routes it. */
    private static final Map<String, Double> SPLIT_WITHIN = Map.of(
            PolicyComparisonSpecs.MEETS_PREFERRED, 0.05,
            PolicyComparisonSpecs.WITHIN_FALLBACK, 0.47,
            PolicyComparisonSpecs.BEYOND_FALLBACK, 0.33,
            PolicyComparisonSpecs.WALK_AWAY, 0.13,
            PolicyComparisonSpecs.UNCLEAR, 0.01,
            PolicyComparisonSpecs.NOT_ADDRESSED, 0.01);
    /** Confident WITHIN (flag mass 0.97) whose worse mass is 0.25: below the margin. */
    private static final Map<String, Double> CLEAN_WITHIN = Map.of(
            PolicyComparisonSpecs.MEETS_PREFERRED, 0.02,
            PolicyComparisonSpecs.WITHIN_FALLBACK, 0.72,
            PolicyComparisonSpecs.BEYOND_FALLBACK, 0.15,
            PolicyComparisonSpecs.WALK_AWAY, 0.10,
            PolicyComparisonSpecs.UNCLEAR, 0.00,
            PolicyComparisonSpecs.NOT_ADDRESSED, 0.01);
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

    private void jevOnLiability(Map<String, Double> probabilities) {
        scriptedProvider.policyComparisonProbabilities = probabilities;
        scriptedProvider.probabilityHeadings.add(LIABILITY);
    }

    private List<Finding> findings(UUID runId) {
        return findingRepository.findFindings(organizationId(), runId);
    }

    private List<Finding> liability(UUID runId, IssueFamily family) {
        return findings(runId).stream().filter(finding -> finding.issueFamily() == family).toList();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> judged(UUID runId) {
        return analysisRepository.findExecutions(organizationId(), runId).stream()
                .filter(execution -> execution.detail().get("positions") instanceof List<?>)
                .flatMap(execution -> ((List<Map<String, Object>>) execution.detail()
                        .get("positions")).stream())
                .toList();
    }

    private long checkedPositions(UUID runId) {
        return judged(runId).stream().filter(position -> position.containsKey("fallbackConditionsOutcome")).count();
    }

    @Test
    void p1OffAnAgreedWorseSecondOpinionLeavesJevsWithinFindingUntagged() throws Exception {
        var reviewId = reviewedContract();
        jevOnLiability(UNSURE_WITHIN);
        generativeProvider.secondOpinionCategory = PolicyComparisonSpecs.BEYOND_FALLBACK;

        var run = analysisService.analyze(organizationId(), reviewId);

        assertThat(generativeProvider.secondOpinionCalls.get()).isEqualTo(2);
        assertThat(liability(run.id(), IssueFamily.POSITION_NEEDS_FALLBACK)).isEmpty();
        assertThat(findings(run.id())).isNotEmpty().allSatisfy(finding ->
                assertThat(finding.quality()).doesNotContainKeys("levers", "severityWithoutLevers",
                        "routeReason", "fallbackConditionsOutcome", "secondOpinionCategory"));
    }

    @Test
    void p2OffAConfidentWithinWithASplitIsNotRouted() throws Exception {
        var reviewId = reviewedContract();
        jevOnLiability(SPLIT_WITHIN);

        var run = analysisService.analyze(organizationId(), reviewId);

        assertThat(generativeProvider.secondOpinionCalls.get()).isZero();
        assertThat(judged(run.id())).isNotEmpty().allSatisfy(position -> assertThat(position)
                .containsEntry("route", "ACCEPT").doesNotContainKey("routeReason"));
    }

    @Test
    void p3OffNoConditionCheckRunsAndWithinStaysWithin() throws Exception {
        var reviewId = reviewedContract();
        jevOnLiability(CLEAN_WITHIN);
        generativeProvider.fallbackConditionLines = "1 | UNMET | {CLAUSE} | the cap is within the fallback multiple";

        var run = analysisService.analyze(organizationId(), reviewId);

        assertThat(generativeProvider.fallbackConditionCalls.get()).isZero();
        assertThat(liability(run.id(), IssueFamily.POSITION_NEEDS_FALLBACK)).isEmpty();
        assertThat(liability(run.id(), IssueFamily.POSITION_ACCEPTABLE_FALLBACK)).isNotEmpty();
    }

    @Test
    void theComparisonRecordsThatTheLeversWereOff() throws Exception {
        var reviewId = reviewedContract();

        var run = analysisService.analyze(organizationId(), reviewId);

        assertThat(analysisRepository.findExecutions(organizationId(), run.id()).stream()
                .filter(execution -> execution.detail().containsKey("positions")).toList())
                .isNotEmpty().allSatisfy(execution -> assertThat(execution.detail().get("levers"))
                        .isEqualTo(Map.of("agreedWorseCategory", false, "withinSplitMargin", -1.0,
                                "fallbackConditions", false, "fallbackConditionsMaxChecks", 40)));
    }
}
