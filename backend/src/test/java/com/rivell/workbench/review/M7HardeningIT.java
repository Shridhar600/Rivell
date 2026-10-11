package com.rivell.workbench.review;

import com.rivell.workbench.taxonomy.CoreConcepts;

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
import com.rivell.workbench.retrieval.EvidenceItem;
import com.rivell.workbench.retrieval.M6EvidenceCorpus;

/**
 * M7 integration-hardening wave: DW-024 secondary-only coverage, DW-027 multi-clause
 * survival honesty, lease-reclaim interleaving, and exact cross-clause source evidence.
 */
@SpringBootTest
@Testcontainers
@Import({LiabilityReviewIT.FakeIntelligenceConfiguration.class,
        LiabilityReviewIT.CorpusConfiguration.class,
        LiabilityReviewIT.MutableIdentityConfiguration.class})
class M7HardeningIT {

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
    ReviewRepository reviewRepository;

    @Autowired
    FindingRepository findingRepository;

    @Autowired
    ContractIngestionService contractIngestionService;

    @Autowired
    ContractEnrichmentService enrichmentService;

    @Autowired
    M6EvidenceCorpus corpusBuilder;

    @Autowired
    LiabilityReviewIT.ReviewScriptedProvider scriptedProvider;

    @Autowired
    JdbcClient jdbcClient;

    @BeforeEach
    void freshOrganization() {
        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));
        scriptedProvider.reset();
    }

    @Test
    void secondaryOnlyRequiredConceptIsPresentButVisiblyUnreviewed() throws Exception {
        var organizationId = organizationId();
        publishMainPolicyAndLibrary(organizationId);
        var review = enrichedReview("m7-msa-secondary-conf.docx",
                ReviewPackRegistry.MSA_PACK);

        var run = analysisService.analyze(organizationId, review.id());

        var confPresence = analysisRepository.findExecutions(organizationId, run.id()).stream()
                .filter(execution -> execution.detail().containsKey("coverage"))
                .filter(execution -> reviewRepository.findPlanChecks(organizationId, review.id())
                        .stream().anyMatch(check -> check.id().equals(execution.planCheckId())
                                && check.concept().equals(CoreConcepts.CONFIDENTIALITY_OBLIGATIONS)))
                .findFirst().orElseThrow();
        assertThat(confPresence.detail()).containsEntry("present", true);
        assertThat(confPresence.detail()).containsEntry("coverage", "PRESENT_SECONDARY_ONLY");
        assertThat(analysisRepository.findSignals(organizationId, run.id()))
                .noneMatch(signal -> signal.signalType() == SignalType.MISSING_REQUIRED_CONCEPT);
        assertThat(reviewRepository.findPlanChecks(organizationId, review.id()))
                .noneMatch(check -> check.mechanism() == CheckMechanism.CONFIDENTIALITY_SURVIVAL);
    }

    @Test
    void primaryConceptReportsPrimaryCoverage() throws Exception {
        var organizationId = organizationId();
        publishMainPolicyAndLibrary(organizationId);
        var review = enrichedReview("m7-msa-clean.docx", ReviewPackRegistry.MSA_PACK);

        var run = analysisService.analyze(organizationId, review.id());

        var confPresence = analysisRepository.findExecutions(organizationId, run.id()).stream()
                .filter(execution -> execution.detail().containsKey("coverage"))
                .filter(execution -> reviewRepository.findPlanChecks(organizationId, review.id())
                        .stream().anyMatch(check -> check.id().equals(execution.planCheckId())
                                && check.concept().equals(CoreConcepts.CONFIDENTIALITY_OBLIGATIONS)))
                .findFirst().orElseThrow();
        assertThat(confPresence.detail()).containsEntry("coverage", "PRESENT_PRIMARY");
        assertThat(reviewRepository.findPlanChecks(organizationId, review.id()))
                .anyMatch(check -> check.mechanism() == CheckMechanism.CONFIDENTIALITY_SURVIVAL);
    }

    @Test
    void twoUncoveredConfidentialityClausesProduceDeterministicGap() throws Exception {
        var organizationId = organizationId();
        publishMainPolicyAndLibrary(organizationId);
        var review = enrichedReview("m7-msa-survival-multi-gap.docx",
                ReviewPackRegistry.MSA_PACK);

        var run = analysisService.analyze(organizationId, review.id());

        var gaps = analysisRepository.findSignals(organizationId, run.id()).stream()
                .filter(signal -> signal.issueFamily() == IssueFamily.SURVIVAL_COVERAGE_GAP)
                .toList();
        assertThat(gaps).hasSize(2);
        assertThat(gaps).allSatisfy(signal -> {
            assertThat(signal.signalType()).isEqualTo(SignalType.CROSS_CLAUSE_CONFLICT);
            assertThat(signal.jevExecutionId()).isNull();
        });
    }

    @Test
    void partialSurvivalCoverageIsUncertaintyNotClean() throws Exception {
        var organizationId = organizationId();
        publishMainPolicyAndLibrary(organizationId);
        var review = enrichedReview("m7-msa-survival-multi-partial.docx",
                ReviewPackRegistry.MSA_PACK);

        var run = analysisService.analyze(organizationId, review.id());

        assertThat(analysisRepository.findSignals(organizationId, run.id()))
                .noneMatch(signal -> signal.issueFamily() == IssueFamily.SURVIVAL_COVERAGE_GAP);
        assertThat(findingRepository.findFindings(organizationId, run.id()))
                .noneMatch(finding -> finding.issueFamily() == IssueFamily.SURVIVAL_COVERAGE_GAP);
        var coverageChecks = analysisRepository.findExecutions(organizationId, run.id()).stream()
                .filter(execution -> execution.detail().containsKey("covered"))
                .toList();
        assertThat(coverageChecks).anySatisfy(execution -> {
            assertThat(execution.status()).isEqualTo(CheckExecutionStatus.DEGRADED);
            assertThat(execution.detail()).containsEntry("covered", false);
            assertThat(execution.detail()).containsEntry("basis", "partial");
            assertThat(execution.detail().get("degradedReason"))
                    .isEqualTo("survival_coverage_partial");
        });
        assertThat(coverageChecks).anySatisfy(execution -> {
            assertThat(execution.status()).isEqualTo(CheckExecutionStatus.COMPLETED);
            assertThat(execution.detail()).containsEntry("covered", true);
        });
    }

    @Test
    void fullyCoveredConfidentialityClausesStayClean() throws Exception {
        var organizationId = organizationId();
        publishMainPolicyAndLibrary(organizationId);
        var review = enrichedReview("m7-msa-survival-multi-covered.docx",
                ReviewPackRegistry.MSA_PACK);

        var run = analysisService.analyze(organizationId, review.id());

        assertThat(analysisRepository.findSignals(organizationId, run.id()))
                .noneMatch(signal -> signal.issueFamily() == IssueFamily.SURVIVAL_COVERAGE_GAP);
    }

    @Test
    void singleCoveredConfidentialityClauseStaysClean() throws Exception {
        var organizationId = organizationId();
        publishMainPolicyAndLibrary(organizationId);
        var review = enrichedReview("m7-msa-clean.docx", ReviewPackRegistry.MSA_PACK);

        var run = analysisService.analyze(organizationId, review.id());

        assertThat(analysisRepository.findSignals(organizationId, run.id()))
                .noneMatch(signal -> signal.issueFamily() == IssueFamily.SURVIVAL_COVERAGE_GAP);
    }

    @Test
    void expiredLeaseReclaimRejectsTheLateWorkerAndKeepsTheWinner() {
        var organizationId = organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);
        var review = reviewService.create(
                corpus.contract().contractId(), 1, ReviewPackRegistry.MSA_PACK);
        var planCheck = reviewRepository.findPlanChecks(organizationId, review.id()).stream()
                .filter(check -> check.mechanism() == CheckMechanism.POLICY_COMPARISON)
                .findFirst().orElseThrow();
        var run = ReviewAnalysisRun.running(UUID.randomUUID(), organizationId, review.id(), 1,
                RunTrigger.INITIAL, ReviewPipeline.VERSION, java.time.Instant.now());
        assertThat(analysisRepository.tryInsertRun(run)).isTrue();
        analysisRepository.insertExecutionIfAbsent(ReviewCheckExecution.pending(
                UUID.randomUUID(), organizationId, review.id(), run.id(), planCheck.id()));
        var execution = analysisRepository.findExecution(organizationId, run.id(),
                planCheck.id()).orElseThrow();

        var leaseA = analysisRepository.claimExecution(organizationId, execution.id())
                .orElseThrow();
        jdbcClient.sql("""
                update review_check_execution set started_at = now() - interval '11 minutes'
                where id = :id
                """).param("id", execution.id()).update();
        var leaseB = analysisRepository.claimExecution(organizationId, execution.id())
                .orElseThrow();
        assertThat(leaseB).isNotEqualTo(leaseA);

        var lateWorker = analysisRepository.completeExecution(organizationId, execution.id(),
                leaseA, CheckExecutionStatus.COMPLETED, null,
                java.util.Map.of("stale", true), 5, java.time.Instant.now());
        assertThat(lateWorker).isFalse();

        var winner = analysisRepository.completeExecution(organizationId, execution.id(),
                leaseB, CheckExecutionStatus.COMPLETED, null,
                java.util.Map.of("authoritative", true), 1, java.time.Instant.now());
        assertThat(winner).isTrue();
        var authoritative = analysisRepository.findExecution(organizationId, run.id(),
                planCheck.id()).orElseThrow();
        assertThat(authoritative.detail()).containsEntry("authoritative", true);
        assertThat(authoritative.detail()).doesNotContainKey("stale");
        assertThat(authoritative.signalCount()).isEqualTo(1);
    }

    @Test
    void crossClauseFindingEvidenceCarriesBothExactClauseSources() throws Exception {
        var organizationId = organizationId();
        publishMainPolicyAndLibrary(organizationId);
        var review = enrichedReview("m7-msa-carveout-conflict.docx",
                ReviewPackRegistry.MSA_PACK);

        var run = analysisService.analyze(organizationId, review.id());

        var finding = findingRepository.findFindings(organizationId, run.id()).stream()
                .filter(candidate -> candidate.issueFamily()
                        == IssueFamily.CARVE_OUT_INCONSISTENCY)
                .findFirst().orElseThrow();
        var items = analysisRepository.findEvidenceItems(organizationId, run.id(),
                finding.evidenceItemIds());
        assertThat(items).extracting(EvidenceItem::evidenceId)
                .containsExactlyInAnyOrderElementsOf(finding.evidenceItemIds());

        var semantics = enrichmentService.getSemantics(review.contractId(),
                review.contractVersionNumber());
        var subjectClauseId = semantics.clauses().stream()
                .filter(clause -> clause.headingNodeId()
                        .equals(finding.subjectHeadingNodeId()))
                .map(clause -> clause.id()).findFirst().orElseThrow();
        var counterpartClauseId = semantics.clauses().stream()
                .filter(clause -> clause.effectivePrimaryConcept()
                        .filter(concept -> concept.equals(CoreConcepts.INDEMNIFICATION))
                        .isPresent())
                .map(clause -> clause.id()).findFirst().orElseThrow();
        assertThat(items).extracting(EvidenceItem::sourceClauseId)
                .contains(subjectClauseId, counterpartClauseId);
    }

    private UUID organizationId() {
        return identityProvider.currentIdentity().organizationId();
    }

    private void publishMainPolicyAndLibrary(UUID organizationId) {
        corpusBuilder.publishAll(organizationId, M6EvidenceCorpus.Fixture.POLICY_MAIN,
                M6EvidenceCorpus.msaManifest2026(), null);
        corpusBuilder.publishAll(organizationId, M6EvidenceCorpus.Fixture.LIBRARY_MAIN,
                M6EvidenceCorpus.msaManifest2026(), null);
    }

    private Review enrichedReview(String fixture, String packKey) throws Exception {
        var bytes = Files.readAllBytes(java.nio.file.Path.of(
                "src/test/resources/fixtures", fixture));
        var ingested = contractIngestionService.ingest(fixture,
                ContractIngestionService.DOCX_CONTENT_TYPE, new ByteArrayInputStream(bytes));
        enrichmentService.enrich(ingested.contractId(), 1,
                new ReviewProfile(ContractType.MSA, PartyRole.CUSTOMER,
                        PaperSource.THIRD_PARTY, Jurisdiction.US, packKey));
        return reviewService.create(ingested.contractId(), 1, null);
    }
}
