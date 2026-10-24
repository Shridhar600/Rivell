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
 * Severity R3 levers (P7) on a review: P1 an agreed second opinion lends its worse category, P2 a
 * confident WITHIN_FALLBACK whose worse mass reaches the margin gets a second opinion, P3 every final
 * WITHIN_FALLBACK is checked against the conditions of its listed fallback. Each lever tags the findings
 * it changes; a lever that cannot answer leaves the finding as it was.
 */
@SpringBootTest
@Testcontainers
@Import({LiabilityReviewIT.FakeIntelligenceConfiguration.class,
        LiabilityReviewIT.CorpusConfiguration.class,
        LiabilityReviewIT.MutableIdentityConfiguration.class})
class SeverityR3LeversIT {

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
        registry.add("rivell.review.fallback-conditions.enabled", () -> "true");
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
    void p1AnAgreedSecondOpinionWithAWorseCategoryRaisesTheFindingAndTagsIt() throws Exception {
        var reviewId = reviewedContract();
        jevOnLiability(UNSURE_WITHIN);
        generativeProvider.secondOpinionCategory = PolicyComparisonSpecs.BEYOND_FALLBACK;

        var run = analysisService.analyze(organizationId(), reviewId);

        var raised = liability(run.id(), IssueFamily.POSITION_NEEDS_FALLBACK);
        assertThat(raised).isNotEmpty().allSatisfy(finding -> {
            assertThat(finding.severity()).isEqualTo(FindingSeverity.MEDIUM);
            assertThat(finding.quality())
                    .containsEntry("route", "SECOND_OPINION")
                    .containsEntry("category", PolicyComparisonSpecs.BEYOND_FALLBACK)
                    .containsEntry("secondOpinionCategory", PolicyComparisonSpecs.BEYOND_FALLBACK)
                    .containsEntry("categoryBeforeLevers", PolicyComparisonSpecs.WITHIN_FALLBACK)
                    .containsEntry("levers", List.of("P1"))
                    .containsEntry("severityWithoutLevers", "LOW");
        });
        assertThat(liability(run.id(), IssueFamily.POSITION_ACCEPTABLE_FALLBACK)).noneSatisfy(finding ->
                assertThat(finding.quality()).containsEntry("secondOpinionCategory",
                        PolicyComparisonSpecs.BEYOND_FALLBACK));
        assertThat(judged(run.id())).anySatisfy(position -> assertThat(position)
                .containsEntry("category", PolicyComparisonSpecs.WITHIN_FALLBACK)
                .containsEntry("finalCategory", PolicyComparisonSpecs.BEYOND_FALLBACK)
                .containsEntry("levers", List.of("P1")));
    }

    @Test
    void p1AnAgreedSecondOpinionWithABetterCategoryLeavesJevsCategoryAndNoTag() throws Exception {
        var reviewId = reviewedContract();
        jevOnLiability(UNSURE_BEYOND);
        generativeProvider.secondOpinionCategory = PolicyComparisonSpecs.WITHIN_FALLBACK;

        var run = analysisService.analyze(organizationId(), reviewId);

        var kept = liability(run.id(), IssueFamily.POSITION_NEEDS_FALLBACK);
        assertThat(kept).isNotEmpty().allSatisfy(finding -> {
            assertThat(finding.severity()).isEqualTo(FindingSeverity.MEDIUM);
            assertThat(finding.quality())
                    .containsEntry("category", PolicyComparisonSpecs.BEYOND_FALLBACK)
                    .containsEntry("secondOpinionCategory", PolicyComparisonSpecs.WITHIN_FALLBACK)
                    .doesNotContainKeys("levers", "severityWithoutLevers");
        });
        // Jev's category stands, so no liability finding turned into a within note
        assertThat(liability(run.id(), IssueFamily.POSITION_ACCEPTABLE_FALLBACK)).noneSatisfy(finding ->
                assertThat(finding.quality()).containsKey("secondOpinionCategory"));
    }

    @Test
    void p2AConfidentWithinWithASplitAtTheMarginGoesToASecondOpinionAndIsTagged() throws Exception {
        var reviewId = reviewedContract();
        jevOnLiability(SPLIT_WITHIN);
        generativeProvider.secondOpinionCategory = PolicyComparisonSpecs.BEYOND_FALLBACK;

        var run = analysisService.analyze(organizationId(), reviewId);

        assertThat(generativeProvider.secondOpinionCalls.get()).isEqualTo(2);
        assertThat(liability(run.id(), IssueFamily.POSITION_NEEDS_FALLBACK)).isNotEmpty().allSatisfy(finding ->
                assertThat(finding.quality())
                        .containsEntry("routeReason", "P2_WITHIN_SPLIT")
                        .containsEntry("levers", List.of("P1", "P2"))
                        .containsEntry("severityWithoutLevers", "LOW"));
        assertThat(judged(run.id())).filteredOn(position -> "ADJUDICATE".equals(position.get("route")))
                .hasSize(2).allSatisfy(position -> assertThat(position)
                        .containsEntry("routeReason", "P2_WITHIN_SPLIT"));
    }

    @Test
    void p2AWithinBelowTheMarginIsNotRouted() throws Exception {
        var reviewId = reviewedContract();
        jevOnLiability(CLEAN_WITHIN);

        var run = analysisService.analyze(organizationId(), reviewId);

        assertThat(generativeProvider.secondOpinionCalls.get()).isZero();
        assertThat(judged(run.id())).noneSatisfy(position -> assertThat(position).containsKey("routeReason"));
    }

    @Test
    void p2ASecondOpinionThatFailsOnARoutedItemLeavesJevsWithinFindingAsItWas() throws Exception {
        var reviewId = reviewedContract();
        jevOnLiability(SPLIT_WITHIN);
        generativeProvider.failSecondOpinion = true;

        var run = analysisService.analyze(organizationId(), reviewId);

        assertThat(generativeProvider.secondOpinionCalls.get()).isEqualTo(2);
        assertThat(findings(run.id())).noneMatch(finding -> finding.issueFamily() == IssueFamily.POSITION_UNCERTAIN
                && finding.quality().containsKey("routeReason"));
        assertThat(liability(run.id(), IssueFamily.POSITION_ACCEPTABLE_FALLBACK)).isNotEmpty().allSatisfy(finding ->
                assertThat(finding.severity()).isEqualTo(FindingSeverity.LOW));
    }

    @Test
    void p2ASecondOpinionThatDisagreesTurnsTheRoutedItemIntoATaggedLookItem() throws Exception {
        var reviewId = reviewedContract();
        jevOnLiability(SPLIT_WITHIN);
        generativeProvider.secondOpinionCategory = PolicyComparisonSpecs.MEETS_PREFERRED;

        var run = analysisService.analyze(organizationId(), reviewId);

        assertThat(liability(run.id(), IssueFamily.POSITION_UNCERTAIN)).isNotEmpty().allSatisfy(finding -> {
            assertThat(finding.severity()).isEqualTo(FindingSeverity.LOW);
            assertThat(finding.quality())
                    .containsEntry("routeReason", "P2_WITHIN_SPLIT")
                    .containsEntry("levers", List.of("P2"))
                    .containsEntry("severityWithoutLevers", "LOW");
        });
    }

    @Test
    void p3AnUnmetConditionWithAQuoteMakesAConfidentWithinBeyondFallbackAndTagsIt() throws Exception {
        var reviewId = reviewedContract();
        jevOnLiability(CLEAN_WITHIN);
        generativeProvider.fallbackConditionLines = "1 | UNMET | {CLAUSE} | the cap is within the fallback multiple";

        var run = analysisService.analyze(organizationId(), reviewId);

        // one call per final WITHIN_FALLBACK, nothing more: every one of them was raised
        var raised = liability(run.id(), IssueFamily.POSITION_NEEDS_FALLBACK);
        assertThat(raised).isNotEmpty();
        assertThat(generativeProvider.fallbackConditionCalls.get()).isGreaterThanOrEqualTo(2)
                .isEqualTo(checkedPositions(run.id()));
        assertThat(liability(run.id(), IssueFamily.POSITION_ACCEPTABLE_FALLBACK)).isEmpty();
        assertThat(raised).allSatisfy(finding -> {
            assertThat(finding.severity()).isEqualTo(FindingSeverity.MEDIUM);
            assertThat(finding.quality())
                    .containsEntry("category", PolicyComparisonSpecs.BEYOND_FALLBACK)
                    .containsEntry("categoryBeforeLevers", PolicyComparisonSpecs.WITHIN_FALLBACK)
                    .containsEntry("levers", List.of("P3"))
                    .containsEntry("severityWithoutLevers", "LOW")
                    .containsEntry("fallbackConditionsOutcome", "BEYOND");
        });
        // the conditions check sees the party and governing-law facts of the contract
        assertThat(generativeProvider.fallbackConditionStates).allSatisfy(state ->
                assertThat(state.facts()).isNotEmpty().anyMatch(fact -> fact.startsWith("Our side")));
    }

    @Test
    void p3AnUnknownConditionKeepsTheFindingWithinWithALookNoteAndNoSeverityChange() throws Exception {
        var reviewId = reviewedContract();
        jevOnLiability(CLEAN_WITHIN);
        generativeProvider.fallbackConditionLines = "1 | UNKNOWN | - | the work is delivered from our UK studio";

        var run = analysisService.analyze(organizationId(), reviewId);

        var kept = liability(run.id(), IssueFamily.POSITION_ACCEPTABLE_FALLBACK);
        assertThat(kept).isNotEmpty();
        assertThat(generativeProvider.fallbackConditionCalls.get()).isGreaterThanOrEqualTo(2)
                .isEqualTo(checkedPositions(run.id()));
        assertThat(liability(run.id(), IssueFamily.POSITION_NEEDS_FALLBACK)).isEmpty();
        assertThat(kept).allSatisfy(finding -> {
            assertThat(finding.severity()).isEqualTo(FindingSeverity.LOW);
            assertThat(finding.quality()).doesNotContainKey("levers")
                    .containsEntry("fallbackConditionsOutcome", "WITHIN")
                    .containsEntry("fallbackConditionsUnknown", List.of("the work is delivered from our UK studio"));
            assertThat(finding.claims()).anySatisfy(claim ->
                    assertThat(claim.text()).contains("the work is delivered from our UK studio"));
        });
    }

    @Test
    void p3AFailedConditionCheckLeavesTheWithinFindingUnchangedAndNeverCrashesTheReview() throws Exception {
        var reviewId = reviewedContract();
        jevOnLiability(CLEAN_WITHIN);
        generativeProvider.failFallbackConditions = true;

        var run = analysisService.analyze(organizationId(), reviewId);

        assertThat(liability(run.id(), IssueFamily.POSITION_NEEDS_FALLBACK)).isEmpty();
        assertThat(liability(run.id(), IssueFamily.POSITION_ACCEPTABLE_FALLBACK)).isNotEmpty().allSatisfy(finding -> {
            assertThat(finding.severity()).isEqualTo(FindingSeverity.LOW);
            assertThat(finding.quality()).containsEntry("fallbackConditionsOutcome", "UNAVAILABLE")
                    .doesNotContainKey("levers");
        });
    }

    @Test
    void everyComparisonRecordsTheLeverSettingsItRanWith() throws Exception {
        var reviewId = reviewedContract();

        var run = analysisService.analyze(organizationId(), reviewId);

        assertThat(analysisRepository.findExecutions(organizationId(), run.id()).stream()
                .filter(execution -> execution.detail().containsKey("positions")).toList())
                .isNotEmpty().allSatisfy(execution -> assertThat(execution.detail().get("levers"))
                        .isEqualTo(Map.of("agreedWorseCategory", true, "withinSplitMargin", 0.45,
                                "fallbackConditions", true, "fallbackConditionsMaxChecks", 40)));
    }
}
