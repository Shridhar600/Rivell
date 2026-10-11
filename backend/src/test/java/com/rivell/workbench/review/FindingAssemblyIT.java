package com.rivell.workbench.review;

import com.rivell.workbench.taxonomy.CoreConcepts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
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

import com.rivell.workbench.contracts.ContractIngestionService;
import com.rivell.workbench.contracts.ContractType;
import com.rivell.workbench.contracts.Jurisdiction;
import com.rivell.workbench.contracts.PaperSource;
import com.rivell.workbench.contracts.PartyRole;
import com.rivell.workbench.contracts.ReviewProfile;
import com.rivell.workbench.organization.UserIdentity;
import com.rivell.workbench.retrieval.M6EvidenceCorpus;

@SpringBootTest
@Testcontainers
@Import({LiabilityReviewIT.FakeIntelligenceConfiguration.class,
        LiabilityReviewIT.CorpusConfiguration.class,
        LiabilityReviewIT.MutableIdentityConfiguration.class})
class FindingAssemblyIT {

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
    org.springframework.jdbc.core.simple.JdbcClient jdbcClient;

    @Autowired
    LiabilityReviewIT.ReviewScriptedProvider scriptedProvider;

    @Autowired
    M6EvidenceCorpus corpusBuilder;

    @Autowired
    ContractIngestionService contractIngestionService;

    @Autowired
    com.rivell.workbench.contracts.ContractEnrichmentService enrichmentService;

    @BeforeEach
    void freshOrganization() {
        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));
        scriptedProvider.reset();
    }

    private UUID organizationId() {
        return identityProvider.currentIdentity().organizationId();
    }

    private Review enrichedReview(String fixture) throws Exception {
        var bytes = Files.readAllBytes(java.nio.file.Path.of(
                "src/test/resources/fixtures", fixture));
        var ingested = contractIngestionService.ingest(fixture,
                ContractIngestionService.DOCX_CONTENT_TYPE, new ByteArrayInputStream(bytes));
        enrichmentService.enrich(ingested.contractId(), 1,
                new ReviewProfile(ContractType.MSA, PartyRole.CUSTOMER,
                        PaperSource.THIRD_PARTY, Jurisdiction.US,
                        ReviewPackRegistry.MSA_PACK));
        return reviewService.create(ingested.contractId(), 1, null);
    }

    private void publishMainCorpus(UUID organizationId) {
        corpusBuilder.publishAll(organizationId, M6EvidenceCorpus.Fixture.POLICY_MAIN,
                M6EvidenceCorpus.msaManifest2026(), null);
        corpusBuilder.publishAll(organizationId, M6EvidenceCorpus.Fixture.LIBRARY_MAIN,
                M6EvidenceCorpus.msaManifest2026(), null);
    }

    @Test
    void deviationConsolidatesDraftingAndEscalationIntoOneFinding() {
        var organizationId = organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);
        var review = reviewService.create(
                corpus.contract().contractId(), 1, ReviewPackRegistry.MSA_PACK);

        var run = analysisService.analyze(organizationId, review.id());

        var findings = findingRepository.findFindings(organizationId, run.id());
        assertThat(findings).hasSize(1);
        var finding = findings.get(0);
        assertThat(finding.issueFamily()).isEqualTo(IssueFamily.POSITION_NEEDS_FALLBACK);
        assertThat(finding.proposedAction()).isEqualTo(ProposedAction.USE_APPROVED_FALLBACK);
        assertThat(finding.proposedLanguageSource())
                .isEqualTo(ProposedLanguageSource.APPROVED_DRAFTING);
        assertThat(finding.proposedLanguage()).contains("two times the fees");
        assertThat(finding.approvedDraftingEvidenceId()).isNotNull();
        assertThat(finding.severity()).isEqualTo(FindingSeverity.MEDIUM);
        assertThat(finding.reviewClassification())
                .isEqualTo(FindingClassification.REVIEW_REQUIRED);
        assertThat(finding.signalIds()).hasSize(3);
        assertThat(finding.claims()).extracting(FindingClaim::claimKind)
                .containsExactly(ClaimKind.OBSERVATION, ClaimKind.POLICY_BASIS,
                        ClaimKind.EVIDENCE_BASIS, ClaimKind.RECOMMENDATION);
        assertThat(finding.claims()).allSatisfy(claim -> {
            assertThat(claim.text()).isNotBlank();
            if (!claim.absence()) {
                assertThat(claim.evidenceItemIds()).isNotEmpty();
            }
        });
        assertThat(finding.evidenceItemIds()).isNotEmpty();
        var progress = analysisService.progress(organizationId, review.id());
        assertThat(progress.findings()).isEqualTo(1);
    }

    @Test
    void prohibitedStructureIsBlockingAndStillProposesApprovedLanguage() throws Exception {
        var organizationId = organizationId();
        publishMainCorpus(organizationId);
        var review = enrichedReview("m7-msa-uncapped.docx");

        var run = analysisService.analyze(organizationId, review.id());

        var finding = findingRepository.findFindings(organizationId, run.id()).stream()
                .filter(candidate -> candidate.issueFamily()
                        == IssueFamily.POSITION_WALK_AWAY)
                .findFirst().orElseThrow();
        assertThat(finding.observationKind()).isEqualTo("POSITION_WALK_AWAY");
        assertThat(finding.severity()).isEqualTo(FindingSeverity.HIGH);
        assertThat(finding.reviewClassification()).isEqualTo(FindingClassification.BLOCKING);
        assertThat(finding.proposedAction()).isEqualTo(ProposedAction.USE_APPROVED_FALLBACK);
    }

    @Test
    void missingAndExpectedProvisionsBecomeAbsenceFindings() throws Exception {
        var organizationId = organizationId();
        var bytes = Files.readAllBytes(java.nio.file.Path.of(
                "src/test/resources/fixtures/m5-nda-counterparty.docx"));
        var ingested = contractIngestionService.ingest("m5-nda-counterparty.docx",
                ContractIngestionService.DOCX_CONTENT_TYPE, new ByteArrayInputStream(bytes));
        enrichmentService.enrich(ingested.contractId(), 1,
                new ReviewProfile(ContractType.NDA, PartyRole.RECEIVING_PARTY,
                        PaperSource.COUNTERPARTY_PAPER, Jurisdiction.US,
                        ReviewPackRegistry.MSA_PACK));
        var review = reviewService.create(ingested.contractId(), 1, null);

        var run = analysisService.analyze(organizationId, review.id());

        var findings = findingRepository.findFindings(organizationId, run.id());
        assertThat(findings).hasSize(2);
        assertThat(findings).allSatisfy(finding -> {
            assertThat(finding.subjectKind()).isEqualTo(FindingSubjectKind.ABSENCE);
            assertThat(finding.subjectHeadingNodeId()).isNull();
            assertThat(finding.absenceConcept()).isNotNull();
            assertThat(finding.proposedLanguageSource())
                    .isEqualTo(ProposedLanguageSource.NONE);
        });
        var missing = findings.stream()
                .filter(finding -> finding.concept().equals(CoreConcepts.LIMITATION_OF_LIABILITY))
                .findFirst().orElseThrow();
        assertThat(missing.severity()).isEqualTo(FindingSeverity.HIGH);
        assertThat(missing.reviewClassification())
                .isEqualTo(FindingClassification.BLOCKING);
        assertThat(missing.proposedAction())
                .isEqualTo(ProposedAction.ADD_REQUIRED_PROVISION);
        assertThat(missing.claims()).anySatisfy(claim -> {
            assertThat(claim.claimKind()).isEqualTo(ClaimKind.ABSENCE_BASIS);
            assertThat(claim.absence()).isTrue();
            assertThat(claim.evidenceItemIds()).isEmpty();
        });
        var expected = findings.stream()
                .filter(finding -> finding.concept().equals(CoreConcepts.INDEMNIFICATION))
                .findFirst().orElseThrow();
        assertThat(expected.reviewClassification())
                .isEqualTo(FindingClassification.INFORMATIONAL);
        assertThat(expected.proposedAction()).isEqualTo(ProposedAction.NO_ACTION);
    }

    @Test
    void noPublishedPositionProducesNoPositionFinding() throws Exception {
        var organizationId = organizationId();
        var review = enrichedReview("m7-msa-preferred.docx");

        var run = analysisService.analyze(organizationId, review.id());

        assertThat(scriptedProvider.policyComparisonDecisions()).isZero();
        assertThat(findingRepository.findFindings(organizationId, run.id()))
                .noneMatch(finding -> finding.issueFamily().name().startsWith("POSITION_"));
        assertThat(analysisRepository.findExecutions(organizationId, run.id()))
                .isNotEmpty()
                .allSatisfy(execution -> assertThat(execution.status())
                        .isEqualTo(CheckExecutionStatus.COMPLETED));
    }

    @Test
    void sameClauseDifferentIssueFamiliesStaySeparateFindings() throws Exception {
        var organizationId = organizationId();
        publishMainCorpus(organizationId);
        var review = enrichedReview("m7-msa-carveout-conflict.docx");

        var run = analysisService.analyze(organizationId, review.id());

        var findings = findingRepository.findFindings(organizationId, run.id());
        assertThat(findings).extracting(Finding::issueFamily)
                .contains(IssueFamily.POSITION_NEEDS_FALLBACK,
                        IssueFamily.CARVE_OUT_INCONSISTENCY);
        var liabilitySubjects = findings.stream()
                .filter(finding -> finding.issueFamily() == IssueFamily.POSITION_NEEDS_FALLBACK
                        || finding.issueFamily() == IssueFamily.CARVE_OUT_INCONSISTENCY)
                .map(Finding::subjectHeadingNodeId).distinct().toList();
        assertThat(liabilitySubjects).hasSize(1);
        var conflict = findings.stream()
                .filter(finding -> finding.issueFamily() == IssueFamily.CARVE_OUT_INCONSISTENCY)
                .findFirst().orElseThrow();
        assertThat(conflict.severity()).isEqualTo(FindingSeverity.HIGH);
        assertThat(conflict.proposedAction()).isEqualTo(ProposedAction.ESCALATE_TO_HUMAN);
    }

    @Test
    void unverifiedGenerativeCandidateIsNotProposed() throws Exception {
        var organizationId = organizationId();
        corpusBuilder.publishAll(organizationId, M6EvidenceCorpus.Fixture.POLICY_MAIN,
                M6EvidenceCorpus.msaManifest2026(), null);
        var review = enrichedReview("m7-msa-uncapped.docx");
        scriptedProvider.verificationSatisfiesProbability = 0.5;

        var run = analysisService.analyze(organizationId, review.id());

        var finding = findingRepository.findFindings(organizationId, run.id()).stream()
                .filter(candidate -> candidate.issueFamily()
                        == IssueFamily.POSITION_WALK_AWAY)
                .findFirst().orElseThrow();
        assertThat(finding.proposedAction()).isEqualTo(ProposedAction.ESCALATE_TO_HUMAN);
        assertThat(finding.proposedLanguageSource())
                .isEqualTo(ProposedLanguageSource.NONE);
        assertThat(finding.quality()).containsEntry("verificationOutcome", "UNCERTAIN");
    }

    @Test
    void failedRecomputeKeepsActiveFindingsAndReportsThem() {
        var organizationId = organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);
        var review = reviewService.create(
                corpus.contract().contractId(), 1, ReviewPackRegistry.MSA_PACK);
        var initial = analysisService.analyze(organizationId, review.id());
        var activeFindings = findingRepository.findFindings(organizationId, initial.id());
        assertThat(activeFindings).isNotEmpty();

        jdbcClient.sql("update contract_semantics set pipeline_version = '' "
                        + "where contract_version_id = :id")
                .param("id", review.contractVersionId()).update();

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> analysisService.recompute(organizationId, review.id()))
                .isInstanceOf(RuntimeException.class);

        assertThat(reviewRepository.find(organizationId, review.id())
                .orElseThrow().status()).isEqualTo(ReviewStatus.COMPLETED);
        assertThat(analysisRepository.findLatestCompletedRun(organizationId, review.id())
                .orElseThrow().id()).isEqualTo(initial.id());
        var progress = analysisService.progress(organizationId, review.id());
        assertThat(progress.findings()).isEqualTo(activeFindings.size());
        assertThat(progress.signals()).isEqualTo(analysisRepository
                .countSignals(organizationId, initial.id()));
        assertThat(progress.pendingChecks()).isZero();
        assertThat(progress.latestRunStatus()).isEqualTo(RunStatus.FAILED);
    }

    @Test
    void findingsAreOrganizationScoped() {
        var organizationId = organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);
        var review = reviewService.create(
                corpus.contract().contractId(), 1, ReviewPackRegistry.MSA_PACK);
        var run = analysisService.analyze(organizationId, review.id());

        var foreign = UUID.randomUUID();
        assertThat(findingRepository.findFindings(foreign, run.id())).isEmpty();
        assertThat(findingRepository.countFindings(foreign, run.id())).isZero();
    }

    @Test
    void survivalGapFindingIsDeterministicAndActionable() throws Exception {
        var organizationId = organizationId();
        corpusBuilder.publishAll(organizationId, M6EvidenceCorpus.Fixture.POLICY_MAIN,
                M6EvidenceCorpus.msaManifest2026(), null);
        var review = enrichedReview("m7-msa-survival-gap.docx");

        var run = analysisService.analyze(organizationId, review.id());

        var finding = findingRepository.findFindings(organizationId, run.id()).stream()
                .filter(candidate -> candidate.issueFamily()
                        == IssueFamily.SURVIVAL_COVERAGE_GAP)
                .findFirst().orElseThrow();
        assertThat(finding.observationKind()).isEqualTo("SURVIVAL_COVERAGE_GAP");
        assertThat(finding.observationText()).contains("not covered by a survival");
        assertThat(finding.severity()).isEqualTo(FindingSeverity.HIGH);
        assertThat(finding.reviewClassification())
                .isEqualTo(FindingClassification.REVIEW_REQUIRED);
        assertThat(finding.proposedAction()).isEqualTo(ProposedAction.ESCALATE_TO_HUMAN);
        assertThat(finding.evidenceItemIds()).isNotEmpty();
    }

    @Test
    void verifiedGenerativeCandidateIsOfferedForHumanConsideration() throws Exception {
        var organizationId = organizationId();
        corpusBuilder.publishAll(organizationId, M6EvidenceCorpus.Fixture.POLICY_MAIN,
                M6EvidenceCorpus.msaManifest2026(), null);
        var review = enrichedReview("m7-msa-uncapped.docx");

        var run = analysisService.analyze(organizationId, review.id());

        var finding = findingRepository.findFindings(organizationId, run.id()).stream()
                .filter(candidate -> candidate.issueFamily()
                        == IssueFamily.POSITION_WALK_AWAY)
                .findFirst().orElseThrow();
        assertThat(finding.proposedAction())
                .isEqualTo(ProposedAction.CONSIDER_GENERATED_LANGUAGE);
        assertThat(finding.proposedLanguageSource())
                .isEqualTo(ProposedLanguageSource.GENERATIVE);
        assertThat(finding.proposedLanguage()).contains("two times the fees");
        assertThat(finding.approvedDraftingEvidenceId()).isNull();
        assertThat(finding.claims()).anySatisfy(claim ->
                assertThat(claim.claimKind()).isEqualTo(ClaimKind.GENERATIVE_EXPLANATION));
    }

    @Test
    void unclearCategoryBecomesReviewAmbiguity() throws Exception {
        var organizationId = organizationId();
        corpusBuilder.publishAll(organizationId, M6EvidenceCorpus.Fixture.POLICY_MAIN,
                M6EvidenceCorpus.msaManifest2026(), null);
        var review = enrichedReview("m7-msa-preferred.docx");
        scriptedProvider.policyComparisonAnswerOverride = PolicyComparisonSpecs.UNCLEAR;
        scriptedProvider.policyComparisonProbabilities = java.util.Map.of(
                PolicyComparisonSpecs.MEETS_PREFERRED, 0.05,
                PolicyComparisonSpecs.WITHIN_FALLBACK, 0.05,
                PolicyComparisonSpecs.BEYOND_FALLBACK, 0.05,
                PolicyComparisonSpecs.WALK_AWAY, 0.05,
                PolicyComparisonSpecs.UNCLEAR, 0.7,
                PolicyComparisonSpecs.NOT_ADDRESSED, 0.1);

        var run = analysisService.analyze(organizationId, review.id());

        var finding = findingRepository.findFindings(organizationId, run.id()).stream()
                .filter(candidate -> candidate.issueFamily() == IssueFamily.POSITION_AMBIGUOUS)
                .findFirst().orElseThrow();
        assertThat(finding.proposedAction()).isEqualTo(ProposedAction.REVIEW_AMBIGUITY);
        assertThat(finding.severity()).isEqualTo(FindingSeverity.MEDIUM);
        assertThat(finding.proposedLanguageSource())
                .isEqualTo(ProposedLanguageSource.NONE);
        assertThat(finding.reviewClassification())
                .isEqualTo(FindingClassification.REVIEW_REQUIRED);
    }

    @Test
    void recomputeKeepsRunHistoryAndReplacesActiveFindings() {
        var organizationId = organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);
        var review = reviewService.create(
                corpus.contract().contractId(), 1, ReviewPackRegistry.MSA_PACK);
        var initial = analysisService.analyze(organizationId, review.id());
        var initialFindings = findingRepository.findFindings(organizationId, initial.id());

        var recomputed = analysisService.recompute(organizationId, review.id());

        assertThat(findingRepository.findFindings(organizationId, initial.id()))
                .hasSameSizeAs(initialFindings);
        var active = findingRepository.findFindings(organizationId, recomputed.id());
        assertThat(active).hasSameSizeAs(initialFindings);
        assertThat(active).extracting(Finding::consolidationKey)
                .containsExactlyElementsOf(initialFindings.stream()
                        .map(Finding::consolidationKey).toList());
        assertThat(active).extracting(Finding::id)
                .doesNotContainAnyElementsOf(initialFindings.stream().map(Finding::id).toList());
    }
}
