package com.rivell.workbench.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.rivell.workbench.contracts.ContractIngestionService;
import com.rivell.workbench.contracts.ContractType;
import com.rivell.workbench.contracts.Jurisdiction;
import com.rivell.workbench.contracts.LegalConcept;
import com.rivell.workbench.contracts.PaperSource;
import com.rivell.workbench.contracts.PartyRole;
import com.rivell.workbench.contracts.ReviewProfile;
import com.rivell.workbench.intelligence.ModelBinding;
import com.rivell.workbench.intelligence.ModelBindingCatalog;
import com.rivell.workbench.intelligence.ModelBindings;
import com.rivell.workbench.intelligence.ModelPolicy;
import com.rivell.workbench.organization.IdentityProvider;
import com.rivell.workbench.organization.UserIdentity;
import com.rivell.workbench.retrieval.M6EvidenceCorpus;

@SpringBootTest
@Testcontainers
class ReviewAnalysisIT {

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

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeIntelligenceConfiguration {

        @Bean
        @Primary
        MutableModelBindingCatalog fakeModelBindingCatalog() {
            return new MutableModelBindingCatalog();
        }

        @Bean
        @Primary
        ModelPolicy fakeModelPolicy(ModelBindingCatalog modelBindingCatalog) {
            return asset -> asset instanceof com.rivell.workbench.intelligence.ReasoningSpec
                    ? modelBindingCatalog.current().generativeProvider()
                    : modelBindingCatalog.current().decisionProvider();
        }

        @Bean
        @Primary
        LiabilityReviewIT.ReviewScriptedProvider fakeSemanticDecisionProvider() {
            return new LiabilityReviewIT.ReviewScriptedProvider();
        }

        @Bean
        @Primary
        com.rivell.workbench.intelligence.GenerativeReasoningProvider fakeGenerativeProvider() {
            return new com.rivell.workbench.contracts.ContractEnrichmentIT.M5FakeGenerativeProvider();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CorpusConfiguration {

        @Bean
        M6EvidenceCorpus m6EvidenceCorpus(
                com.rivell.workbench.knowledge.KnowledgeIngestionService ingestionService,
                com.rivell.workbench.knowledge.CandidateReviewService reviewService,
                com.rivell.workbench.knowledge.KnowledgePublicationService publicationService,
                com.rivell.workbench.knowledge.KnowledgeQueryService queryService,
                ContractIngestionService contractIngestionService,
                com.rivell.workbench.contracts.ContractEnrichmentService enrichmentService) {
            return new M6EvidenceCorpus(ingestionService, reviewService, publicationService,
                    queryService, contractIngestionService, enrichmentService);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MutableIdentityConfiguration {

        @Bean
        @Primary
        MutableIdentity mutableIdentityProvider() {
            return new MutableIdentity();
        }
    }

    static final class MutableModelBindingCatalog implements ModelBindingCatalog {

        static final ModelBindings INITIAL = new ModelBindings(
                new ModelBinding("fake", "fake-decision-model"),
                new ModelBinding("fake", "fake-gen-model"));

        volatile ModelBindings current = INITIAL;

        @Override
        public ModelBindings current() {
            return current;
        }
    }

    static final class MutableIdentity implements IdentityProvider {

        volatile UserIdentity current = new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN"));

        void use(UserIdentity identity) {
            current = identity;
        }

        @Override
        public UserIdentity currentIdentity() {
            return current;
        }
    }

    @Autowired
    MutableIdentity identityProvider;

    @Autowired
    MutableModelBindingCatalog modelBindingCatalog;

    @Autowired
    LiabilityReviewIT.ReviewScriptedProvider scriptedProvider;

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
    M6EvidenceCorpus corpusBuilder;

    @Autowired
    ContractIngestionService contractIngestionService;

    @Autowired
    com.rivell.workbench.contracts.ContractEnrichmentService enrichmentService;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    org.jobrunr.storage.StorageProvider storageProvider;

    @BeforeEach
    void freshOrganization() {
        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));
        modelBindingCatalog.current = MutableModelBindingCatalog.INITIAL;
        scriptedProvider.reset();
    }

    private UUID organizationId() {
        return identityProvider.currentIdentity().organizationId();
    }

    @Test
    void analysisCompletesReviewAndEnqueuesTheJobAfterCommit() {
        var organizationId = organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);
        var review = reviewService.create(
                corpus.contract().contractId(), 1, ReviewPackRegistry.MSA_PACK);

        var enqueued = storageProvider.getJobList(
                org.jobrunr.jobs.states.StateName.ENQUEUED,
                new org.jobrunr.storage.navigation.AmountRequest("updatedAt:DESC", 100));
        assertThat(enqueued).anyMatch(job ->
                job.getJobSignature().contains("ReviewAnalysisJobHandler"));

        var run = analysisService.analyze(organizationId, review.id());

        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(reviewRepository.find(organizationId, review.id())
                .orElseThrow().status()).isEqualTo(ReviewStatus.COMPLETED);

        var checks = reviewRepository.findPlanChecks(organizationId, review.id());
        var executions = analysisRepository.findExecutions(organizationId, run.id());
        assertThat(executions).hasSize(checks.size());
        assertThat(executions).allSatisfy(execution -> {
            assertThat(execution.detail()).isNotEmpty();
            if (execution.status() != CheckExecutionStatus.COMPLETED) {
                assertThat(execution.detail().get("degradedReason"))
                        .isEqualTo("survival_coverage_partial");
            }
        });
        // this configuration's published policy carries no escalation trigger; the
        // bounded semantic path is still exercised (no zero-signal fake artefact)
        var signals = analysisRepository.findSignals(organizationId, run.id());
        assertThat(signals).extracting(RawReviewSignal::signalType)
                .containsExactlyInAnyOrder(SignalType.POLICY_DEVIATION,
                        SignalType.APPROVED_DRAFTING_AVAILABLE);

        var progress = analysisService.progress(organizationId, review.id());
        assertThat(progress.reviewStatus()).isEqualTo(ReviewStatus.COMPLETED);
        assertThat(progress.plannedChecks()).isEqualTo(checks.size());
        assertThat(progress.completedChecks() + progress.degradedChecks())
                .isEqualTo(progress.plannedChecks());
        assertThat(progress.degradedChecks()).isEqualTo(1);
        assertThat(progress.pendingChecks()).isZero();
        assertThat(progress.runningChecks()).isZero();
    }

    @Test
    void missingRequiredAndExpectedConceptsProduceAbsenceSignals() throws Exception {
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

        var signals = analysisRepository.findSignals(organizationId, run.id());
        assertThat(signals).extracting(RawReviewSignal::signalType,
                        RawReviewSignal::issueFamily, RawReviewSignal::absenceConcept)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(SignalType.MISSING_REQUIRED_CONCEPT,
                                IssueFamily.MISSING_REQUIRED_PROVISION,
                                LegalConcept.LIMITATION_OF_LIABILITY),
                        org.assertj.core.groups.Tuple.tuple(SignalType.EXPECTED_CONCEPT_ABSENT,
                                IssueFamily.EXPECTED_PROVISION_ABSENT,
                                LegalConcept.INDEMNIFICATION));
        assertThat(signals).allSatisfy(signal -> {
            assertThat(signal.subjectKind()).isEqualTo(FindingSubjectKind.ABSENCE);
            assertThat(signal.subjectHeadingNodeId()).isNull();
            assertThat(signal.payload()).isInstanceOf(PresencePayload.class);
        });

        var progress = analysisService.progress(organizationId, review.id());
        assertThat(progress.signals()).isEqualTo(2);
        assertThat(progress.reviewStatus()).isEqualTo(ReviewStatus.COMPLETED);
    }

    @Test
    void retryResumesTheSameRunWithoutDuplicatingSignals() {
        var organizationId = organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);
        var review = reviewService.create(
                corpus.contract().contractId(), 1, ReviewPackRegistry.MSA_PACK);

        var first = analysisService.analyze(organizationId, review.id());
        var executionsAfterFirst = analysisRepository.findExecutions(organizationId, first.id());
        var signalsAfterFirst = analysisRepository.countSignals(organizationId, first.id());
        var findingKeysAfterFirst = findingRepository.findFindings(organizationId, first.id())
                .stream().map(Finding::consolidationKey).toList();
        assertThat(signalsAfterFirst).isPositive();
        assertThat(findingKeysAfterFirst).isNotEmpty();
        assertSignalCountParity(organizationId, first.id());

        var second = analysisService.analyze(organizationId, review.id());
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(analysisRepository.findExecutions(organizationId, first.id()))
                .hasSize(executionsAfterFirst.size());
        assertThat(analysisRepository.countSignals(organizationId, first.id()))
                .isEqualTo(signalsAfterFirst);

        var interrupted = liabilityExecution(organizationId, review.id(), first.id());
        var interruptedSignalIds = analysisRepository.findSignals(organizationId, first.id())
                .stream().filter(signal -> signal.planCheckId().equals(interrupted.planCheckId()))
                .map(RawReviewSignal::id).toList();
        assertThat(interruptedSignalIds).isNotEmpty();
        jdbcClient.sql("""
                update review_check_execution
                set status = 'RUNNING', started_at = now() - interval '11 minutes',
                    completed_at = null
                where id = :id
                """).param("id", interrupted.id()).update();
        jdbcClient.sql("""
                update review_analysis_run set status = 'RUNNING', completed_at = null
                where id = :id
                """).param("id", first.id()).update();
        jdbcClient.sql("""
                update review set status = 'ANALYZING', completed_at = null
                where id = :id
                """).param("id", review.id()).update();

        var resumed = analysisService.analyze(organizationId, review.id());
        assertThat(resumed.id()).isEqualTo(first.id());
        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(analysisRepository.findExecutions(organizationId, first.id()))
                .hasSize(executionsAfterFirst.size());
        assertThat(analysisRepository.countSignals(organizationId, first.id()))
                .isEqualTo(signalsAfterFirst);
        assertSignalCountParity(organizationId, first.id());
        assertThat(findingRepository.findFindings(organizationId, first.id())
                .stream().map(Finding::consolidationKey).toList())
                .containsExactlyElementsOf(findingKeysAfterFirst);

        var replaced = analysisRepository.findSignals(organizationId, first.id()).stream()
                .filter(signal -> signal.planCheckId().equals(interrupted.planCheckId()))
                .toList();
        assertThat(replaced).isNotEmpty();
        assertThat(replaced).extracting(RawReviewSignal::id)
                .doesNotContainAnyElementsOf(interruptedSignalIds);
        assertThat(analysisRepository.findSignals(organizationId, first.id()))
                .extracting(RawReviewSignal::dedupeKey).doesNotHaveDuplicates();
    }

    private ReviewCheckExecution liabilityExecution(
            UUID organizationId, UUID reviewId, UUID runId) {
        var planCheck = reviewRepository.findPlanChecks(organizationId, reviewId).stream()
                .filter(check -> check.mechanism() == CheckMechanism.LIABILITY_CAP)
                .findFirst().orElseThrow();
        return analysisRepository.findExecution(organizationId, runId, planCheck.id())
                .orElseThrow();
    }

    private void assertSignalCountParity(UUID organizationId, UUID runId) {
        for (var execution : analysisRepository.findExecutions(organizationId, runId)) {
            assertThat(execution.signalCount()).isEqualTo(analysisRepository.countSignals(
                    organizationId, runId, execution.planCheckId()));
        }
    }

    @Test
    void semanticsChangeAfterCreationDegradesAllChecks() {
        var organizationId = organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);
        var review = reviewService.create(
                corpus.contract().contractId(), 1, ReviewPackRegistry.MSA_PACK);

        enrichmentService.enrich(corpus.contract().contractId(), 1,
                new ReviewProfile(ContractType.MSA, PartyRole.CUSTOMER,
                        PaperSource.THIRD_PARTY, Jurisdiction.US,
                        ReviewPackRegistry.NDA_PACK));

        var run = analysisService.analyze(organizationId, review.id());

        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        var executions = analysisRepository.findExecutions(organizationId, run.id());
        assertThat(executions).isNotEmpty();
        assertThat(executions).allSatisfy(execution -> {
            assertThat(execution.status()).isEqualTo(CheckExecutionStatus.DEGRADED);
            assertThat(execution.detail().get("degradedReason").toString())
                    .contains("semantics changed");
        });
        assertThat(analysisRepository.countSignals(organizationId, run.id())).isZero();
        assertThat(reviewRepository.find(organizationId, review.id())
                .orElseThrow().status()).isEqualTo(ReviewStatus.COMPLETED);
    }

    @Test
    void analysisIsOrganizationScoped() {
        var organizationId = organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);
        var review = reviewService.create(
                corpus.contract().contractId(), 1, ReviewPackRegistry.MSA_PACK);

        var otherOrganization = UUID.randomUUID();
        assertThatThrownBy(() -> analysisService.analyze(otherOrganization, review.id()))
                .isInstanceOf(ReviewNotFoundException.class);
        assertThatThrownBy(() -> analysisService.progress(otherOrganization, review.id()))
                .isInstanceOf(ReviewNotFoundException.class);
        assertThat(reviewRepository.find(otherOrganization, review.id())).isEmpty();
        assertThat(analysisRepository.findExecutions(otherOrganization, UUID.randomUUID()))
                .isEmpty();
        assertThat(analysisRepository.findSignals(otherOrganization, UUID.randomUUID()))
                .isEmpty();
    }

    @Test
    void crashAfterRunCompletionFinishesTheReviewWithoutANewRun() {
        var organizationId = organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);
        var review = reviewService.create(
                corpus.contract().contractId(), 1, ReviewPackRegistry.MSA_PACK);
        var first = analysisService.analyze(organizationId, review.id());

        jdbcClient.sql("""
                update review set status = 'ANALYZING', completed_at = null
                where id = :id
                """).param("id", review.id()).update();

        var resumed = analysisService.analyze(organizationId, review.id());
        assertThat(resumed.id()).isEqualTo(first.id());
        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(reviewRepository.find(organizationId, review.id())
                .orElseThrow().status()).isEqualTo(ReviewStatus.COMPLETED);
        assertThat(analysisRepository.findExecutions(organizationId, first.id()))
                .hasSize(reviewRepository.findPlanChecks(organizationId, review.id()).size());
    }

    @Test
    void freshClaimOnAnActivelyRunningCheckIsSkippedUntilTheLeaseExpires() {
        var organizationId = organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);
        var review = reviewService.create(corpus.contract().contractId(), 1, null);
        var first = analysisService.analyze(organizationId, review.id());
        var execution = liabilityExecution(organizationId, review.id(), first.id());

        jdbcClient.sql("""
                update review_check_execution
                set status = 'RUNNING', started_at = now(), completed_at = null
                where id = :id
                """).param("id", execution.id()).update();
        jdbcClient.sql("""
                update review_analysis_run set status = 'RUNNING', completed_at = null
                where id = :id
                """).param("id", first.id()).update();
        jdbcClient.sql("""
                update review set status = 'ANALYZING', completed_at = null
                where id = :id
                """).param("id", review.id()).update();

        analysisService.analyze(organizationId, review.id());
        assertThat(analysisRepository.findExecution(
                organizationId, first.id(), execution.planCheckId()).orElseThrow().status())
                .isEqualTo(CheckExecutionStatus.RUNNING);
        assertThat(reviewRepository.find(organizationId, review.id())
                .orElseThrow().status()).isEqualTo(ReviewStatus.ANALYZING);

        jdbcClient.sql("""
                update review_check_execution
                set started_at = now() - interval '11 minutes' where id = :id
                """).param("id", execution.id()).update();
        analysisService.analyze(organizationId, review.id());
        assertThat(reviewRepository.find(organizationId, review.id())
                .orElseThrow().status()).isEqualTo(ReviewStatus.COMPLETED);
    }

    @Test
    void unrecoverableFailureMarksReviewAndRunFailed() {
        var organizationId = organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);
        var review = reviewService.create(
                corpus.contract().contractId(), 1, ReviewPackRegistry.MSA_PACK);

        jdbcClient.sql("delete from review_snapshot where review_id = :id")
                .param("id", review.id()).update();

        assertThatThrownBy(() -> analysisService.analyze(organizationId, review.id()))
                .isInstanceOf(IllegalStateException.class);
        var failed = reviewRepository.find(organizationId, review.id()).orElseThrow();
        assertThat(failed.status()).isEqualTo(ReviewStatus.FAILED);
        assertThat(failed.failureReason()).contains("IllegalStateException");
    }

    @Test
    void recomputeCreatesANewRunAndRetainsHistory() {
        var organizationId = organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);
        var review = reviewService.create(
                corpus.contract().contractId(), 1, ReviewPackRegistry.MSA_PACK);
        var initial = analysisService.analyze(organizationId, review.id());
        var initialSignals = analysisRepository.findSignals(organizationId, initial.id());

        var recomputed = analysisService.recompute(organizationId, review.id());

        assertThat(recomputed.id()).isNotEqualTo(initial.id());
        assertThat(recomputed.runNumber()).isEqualTo(initial.runNumber() + 1);
        assertThat(recomputed.trigger()).isEqualTo(RunTrigger.RECOMPUTE);
        assertThat(recomputed.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(analysisRepository.findLatestRun(organizationId, review.id())
                .orElseThrow().id()).isEqualTo(recomputed.id());
        assertThat(analysisRepository.findSignals(organizationId, initial.id()))
                .hasSameSizeAs(initialSignals);
        assertThat(analysisRepository.findSignals(organizationId, recomputed.id()))
                .hasSameSizeAs(initialSignals);
        assertThat(reviewRepository.find(organizationId, review.id())
                .orElseThrow().status()).isEqualTo(ReviewStatus.COMPLETED);
        assertSignalCountParity(organizationId, recomputed.id());
    }

    @Test
    void recomputeRejectsANonTerminalReview() {
        var organizationId = organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);
        var review = reviewService.create(corpus.contract().contractId(), 1, null);

        assertThatThrownBy(() -> analysisService.recompute(organizationId, review.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COMPLETED");
    }

    @Test
    void modelBindingChangeAfterCreationDegradesAllChecks() {
        var organizationId = organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);
        var review = reviewService.create(
                corpus.contract().contractId(), 1, ReviewPackRegistry.MSA_PACK);

        modelBindingCatalog.current = new ModelBindings(
                new ModelBinding("fake", "rebound-decision-model"),
                new ModelBinding("fake", "fake-gen-model"));

        var run = analysisService.analyze(organizationId, review.id());

        var executions = analysisRepository.findExecutions(organizationId, run.id());
        assertThat(executions).isNotEmpty();
        assertThat(executions).allSatisfy(execution -> {
            assertThat(execution.status()).isEqualTo(CheckExecutionStatus.DEGRADED);
            assertThat(execution.detail().get("degradedReason").toString())
                    .contains("model policy bindings changed");
        });
        assertThat(analysisRepository.countSignals(organizationId, run.id())).isZero();
        assertThat(reviewRepository.find(organizationId, review.id())
                .orElseThrow().status()).isEqualTo(ReviewStatus.COMPLETED);
    }

    @Test
    void failureAfterRunAcquisitionPersistsRunAndReviewFailed() {
        var organizationId = organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);
        var review = reviewService.create(
                corpus.contract().contractId(), 1, ReviewPackRegistry.MSA_PACK);

        jdbcClient.sql("""
                update contract_semantics set pipeline_version = ''
                where contract_version_id = :id
                """).param("id", review.contractVersionId()).update();

        assertThatThrownBy(() -> analysisService.analyze(organizationId, review.id()))
                .isInstanceOf(RuntimeException.class);
        var failedReview = reviewRepository.find(organizationId, review.id()).orElseThrow();
        assertThat(failedReview.status()).isEqualTo(ReviewStatus.FAILED);
        assertThat(failedReview.failureReason()).isNotBlank();
        assertThat(failedReview.completedAt()).isNotNull();
        var run = analysisRepository.findLatestRun(organizationId, review.id()).orElseThrow();
        assertThat(run.status()).isEqualTo(RunStatus.FAILED);
        assertThat(run.failureReason()).isNotBlank();
        assertThat(run.completedAt()).isNotNull();
    }

    @Test
    void staleLeaseTokenCannotWriteTerminalState() {
        var organizationId = organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);
        var review = reviewService.create(
                corpus.contract().contractId(), 1, ReviewPackRegistry.MSA_PACK);
        var planCheck = reviewRepository.findPlanChecks(organizationId, review.id()).stream()
                .filter(check -> check.mechanism() == CheckMechanism.LIABILITY_CAP)
                .findFirst().orElseThrow();
        var run = ReviewAnalysisRun.running(UUID.randomUUID(), organizationId, review.id(), 1,
                RunTrigger.INITIAL, ReviewPipeline.VERSION, java.time.Instant.now());
        assertThat(analysisRepository.tryInsertRun(run)).isTrue();
        analysisRepository.insertExecutionIfAbsent(ReviewCheckExecution.pending(
                UUID.randomUUID(), organizationId, review.id(), run.id(), planCheck.id()));
        var execution = analysisRepository.findExecution(organizationId, run.id(),
                planCheck.id()).orElseThrow();
        var lease = analysisRepository.claimExecution(organizationId, execution.id())
                .orElseThrow();

        var loser = analysisRepository.completeExecution(organizationId, execution.id(),
                UUID.randomUUID(), CheckExecutionStatus.COMPLETED, null,
                java.util.Map.of("loser", true), 99, java.time.Instant.now());

        assertThat(loser).isFalse();
        var untouched = analysisRepository.findExecution(organizationId, run.id(),
                planCheck.id()).orElseThrow();
        assertThat(untouched.status()).isEqualTo(CheckExecutionStatus.RUNNING);
        assertThat(untouched.detail()).doesNotContainKey("loser");
        assertThat(untouched.signalCount()).isZero();

        var winner = analysisRepository.completeExecution(organizationId, execution.id(),
                lease, CheckExecutionStatus.COMPLETED, null,
                java.util.Map.of("winner", true), 0, java.time.Instant.now());
        assertThat(winner).isTrue();
        var completed = analysisRepository.findExecution(organizationId, run.id(),
                planCheck.id()).orElseThrow();
        assertThat(completed.status()).isEqualTo(CheckExecutionStatus.COMPLETED);
        assertThat(completed.detail()).containsEntry("winner", true);
    }

    @Test
    void emptyPlanCompletesWithZeroFindings() throws Exception {
        var organizationId = organizationId();
        var bytes = Files.readAllBytes(java.nio.file.Path.of(
                "src/test/resources/fixtures/m5-nda-counterparty.docx"));
        var ingested = contractIngestionService.ingest("m5-nda-counterparty.docx",
                ContractIngestionService.DOCX_CONTENT_TYPE, new ByteArrayInputStream(bytes));
        enrichmentService.enrich(ingested.contractId(), 1,
                new ReviewProfile(ContractType.NDA, PartyRole.RECEIVING_PARTY,
                        PaperSource.COUNTERPARTY_PAPER, Jurisdiction.US));
        var review = reviewService.create(ingested.contractId(), 1, null);

        assertThat(reviewRepository.findPlanChecks(organizationId, review.id())).isEmpty();

        var run = analysisService.analyze(organizationId, review.id());

        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(analysisRepository.findSignals(organizationId, run.id())).isEmpty();
        assertThat(findingRepository.findFindings(organizationId, run.id())).isEmpty();
        var progress = analysisService.progress(organizationId, review.id());
        assertThat(progress.plannedChecks()).isZero();
        assertThat(progress.reviewStatus()).isEqualTo(ReviewStatus.COMPLETED);
    }

    @Test
    void analysisForAnotherIdentityOrganizationStillWorks() {
        var organizationId = organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);
        var review = reviewService.create(
                corpus.contract().contractId(), 1, ReviewPackRegistry.MSA_PACK);

        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));

        var run = analysisService.analyze(organizationId, review.id());
        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(reviewRepository.find(organizationId, review.id())
                .orElseThrow().status()).isEqualTo(ReviewStatus.COMPLETED);
    }
}
