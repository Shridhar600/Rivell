package com.rivell.workbench.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.rivell.workbench.organization.UserIdentity;
import com.rivell.workbench.platform.jobs.ReviewAnalysisIncompleteException;
import com.rivell.workbench.platform.jobs.ReviewAnalysisJobHandler;
import com.rivell.workbench.platform.jobs.ReviewAnalysisRetryExhaustedFilter;
import com.rivell.workbench.retrieval.EvidenceItem;
import com.rivell.workbench.retrieval.M6EvidenceCorpus;
import com.rivell.workbench.workflow.api.ReviewDiagnosticsService;

/**
 * R4/R2: a transient infrastructure error at a check's terminal write must not kill the run. The
 * run stays RUNNING, the transient counter records it, the failed check's lease is released, and
 * the next {@code analyze} resumes the same run without repeating a finished check. The terminal
 * paths (the third transient failure, a non-transient failure) and the retry-exhausted filter are
 * covered here too, because they share the same database fixture.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = "rivell.review.omissions=true")
@Import({LiabilityReviewIT.FakeIntelligenceConfiguration.class,
        LiabilityReviewIT.CorpusConfiguration.class,
        LiabilityReviewIT.MutableIdentityConfiguration.class,
        ReviewResumeIT.FaultInjectionConfiguration.class})
class ReviewResumeIT {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("pgvector/pgvector:0.8.6-pg17-trixie")
            .asCompatibleSubstituteFor("postgres");

    private static final String EXHAUSTED_REASON = "analysis_retries_exhausted";

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
    static class FaultInjectionConfiguration {

        @Bean
        CompleteExecutionFault completeExecutionFault() {
            return new CompleteExecutionFault();
        }

        @Bean
        @Primary
        ReviewAnalysisRepository faultingReviewAnalysisRepository(
                @Qualifier("jdbcReviewAnalysisRepository") ReviewAnalysisRepository delegate,
                CompleteExecutionFault fault) {
            return new FaultingReviewAnalysisRepository(delegate, fault);
        }
    }

    /**
     * Arms the next attempt's Nth terminal write. The write is delegated first (inside the
     * caller's transaction) and the fault is thrown afterwards, so the test also proves that the
     * failed work rolls back while the counter written by the service survives.
     */
    static final class CompleteExecutionFault {

        private final AtomicInteger calls = new AtomicInteger();
        private volatile int failOnCall = -1;
        private volatile RuntimeException failure;
        private volatile RuntimeException counterFailure;
        private final AtomicInteger findExecutionsCalls = new AtomicInteger();
        private volatile int failFindExecutionsOnCall = -1;
        private volatile RuntimeException findExecutionsFailure;

        void failOnCall(int call, RuntimeException failure) {
            this.calls.set(0);
            this.failOnCall = call;
            this.failure = failure;
        }

        void failFindExecutionsOnCall(int call, RuntimeException failure) {
            this.findExecutionsCalls.set(0);
            this.failFindExecutionsOnCall = call;
            this.findExecutionsFailure = failure;
        }

        void failCounterWith(RuntimeException failure) {
            this.counterFailure = failure;
        }

        void allowCounter() {
            this.counterFailure = null;
        }

        void disarm() {
            this.failOnCall = -1;
            this.failure = null;
            this.failFindExecutionsOnCall = -1;
            this.findExecutionsFailure = null;
        }

        void reset() {
            disarm();
            this.counterFailure = null;
        }

        void afterCompleteExecution() {
            if (failOnCall < 0 || calls.incrementAndGet() != failOnCall) {
                return;
            }
            var pending = failure;
            disarm();
            throw pending;
        }

        void beforeFindExecutions() {
            if (failFindExecutionsOnCall < 0
                    || findExecutionsCalls.incrementAndGet() != failFindExecutionsOnCall) {
                return;
            }
            var pending = findExecutionsFailure;
            failFindExecutionsOnCall = -1;
            throw pending;
        }

        void beforeRecordTransientFailure() {
            var pending = counterFailure;
            if (pending != null) {
                throw pending;
            }
        }
    }

    static final class FaultingReviewAnalysisRepository implements ReviewAnalysisRepository {

        private final ReviewAnalysisRepository delegate;
        private final CompleteExecutionFault fault;

        FaultingReviewAnalysisRepository(ReviewAnalysisRepository delegate,
                CompleteExecutionFault fault) {
            this.delegate = delegate;
            this.fault = fault;
        }

        @Override
        public Optional<ReviewAnalysisRun> findRunningRun(UUID organizationId, UUID reviewId) {
            return delegate.findRunningRun(organizationId, reviewId);
        }

        @Override
        public Optional<ReviewAnalysisRun> findLatestRun(UUID organizationId, UUID reviewId) {
            return delegate.findLatestRun(organizationId, reviewId);
        }

        @Override
        public Optional<ReviewAnalysisRun> findLatestCompletedRun(
                UUID organizationId, UUID reviewId) {
            return delegate.findLatestCompletedRun(organizationId, reviewId);
        }

        @Override
        public int maxRunNumber(UUID organizationId, UUID reviewId) {
            return delegate.maxRunNumber(organizationId, reviewId);
        }

        @Override
        public boolean tryInsertRun(ReviewAnalysisRun run) {
            return delegate.tryInsertRun(run);
        }

        @Override
        public boolean markRunCompleted(UUID organizationId, UUID runId, Instant completedAt) {
            return delegate.markRunCompleted(organizationId, runId, completedAt);
        }

        @Override
        public boolean markRunFailed(UUID organizationId, UUID runId, String reason,
                Instant completedAt) {
            return delegate.markRunFailed(organizationId, runId, reason, completedAt);
        }

        @Override
        public OptionalInt recordTransientFailure(UUID organizationId, UUID runId, String reason,
                UUID executionId, UUID leaseId) {
            fault.beforeRecordTransientFailure();
            return delegate.recordTransientFailure(organizationId, runId, reason, executionId,
                    leaseId);
        }

        @Override
        public Optional<ReviewCheckExecution> findExecution(
                UUID organizationId, UUID runId, UUID planCheckId) {
            return delegate.findExecution(organizationId, runId, planCheckId);
        }

        @Override
        public List<ReviewCheckExecution> findExecutions(UUID organizationId, UUID runId) {
            fault.beforeFindExecutions();
            return delegate.findExecutions(organizationId, runId);
        }

        @Override
        public boolean insertExecutionIfAbsent(ReviewCheckExecution execution) {
            return delegate.insertExecutionIfAbsent(execution);
        }

        @Override
        public Optional<UUID> claimExecution(UUID organizationId, UUID executionId) {
            return delegate.claimExecution(organizationId, executionId);
        }

        @Override
        public boolean completeExecution(
                UUID organizationId, UUID executionId, UUID leaseId,
                CheckExecutionStatus status, UUID evidenceTraceId, Map<String, Object> detail,
                int signalCount, Instant completedAt) {
            var completed = delegate.completeExecution(organizationId, executionId, leaseId,
                    status, evidenceTraceId, detail, signalCount, completedAt);
            fault.afterCompleteExecution();
            return completed;
        }

        @Override
        public void insertSignals(List<RawReviewSignal> signals) {
            delegate.insertSignals(signals);
        }

        @Override
        public int deleteSignals(UUID organizationId, UUID runId, UUID planCheckId) {
            return delegate.deleteSignals(organizationId, runId, planCheckId);
        }

        @Override
        public List<RawReviewSignal> findSignals(UUID organizationId, UUID runId) {
            return delegate.findSignals(organizationId, runId);
        }

        @Override
        public int countSignals(UUID organizationId, UUID runId) {
            return delegate.countSignals(organizationId, runId);
        }

        @Override
        public int countSignals(UUID organizationId, UUID runId, UUID planCheckId) {
            return delegate.countSignals(organizationId, runId, planCheckId);
        }

        @Override
        public void replaceEvidenceItems(
                UUID organizationId, UUID reviewId, UUID runId, UUID planCheckId,
                List<EvidenceItem> items) {
            delegate.replaceEvidenceItems(organizationId, reviewId, runId, planCheckId, items);
        }

        @Override
        public List<EvidenceItem> findEvidenceItems(
                UUID organizationId, UUID runId, Collection<UUID> evidenceItemIds) {
            return delegate.findEvidenceItems(organizationId, runId, evidenceItemIds);
        }
    }

    record LeaseRow(String status, UUID leaseId, Instant startedAt) {
    }

    @Autowired
    LiabilityReviewIT.MutableIdentity identityProvider;

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
    ReviewDiagnosticsService diagnosticsService;

    @Autowired
    ReviewAnalysisRetryExhaustedFilter retryExhaustedFilter;

    @Autowired
    ReviewAnalysisJobHandler jobHandler;

    @Autowired
    CompleteExecutionFault fault;

    @Autowired
    M6EvidenceCorpus corpusBuilder;

    @Autowired
    org.jobrunr.storage.StorageProvider storageProvider;

    @Autowired
    JdbcClient jdbcClient;

    @BeforeEach
    void freshOrganization() {
        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));
        scriptedProvider.reset();
        fault.reset();
    }

    private UUID organizationId() {
        return identityProvider.currentIdentity().organizationId();
    }

    private Review reviewWithoutPack(UUID organizationId) {
        var corpus = corpusBuilder.buildMainCorpus(organizationId);
        // No pack: the plan is one policy comparison per clause, so "check 1" and "check 2" are
        // model-using checks and a finished check's model calls are observable.
        return reviewService.create(corpus.contract().contractId(), 1, null);
    }

    @Test
    void aTransientTerminalWriteKeepsTheRunAndTheNextAnalyzeResumesWithoutRedoingCheckOne() {
        var organizationId = organizationId();
        var review = reviewWithoutPack(organizationId);
        var checks = reviewRepository.findPlanChecks(organizationId, review.id());
        assertThat(checks).hasSizeGreaterThan(1);

        fault.failOnCall(2, new TransientDataAccessResourceException("connection reset"));

        assertThatThrownBy(() -> analysisService.analyze(organizationId, review.id()))
                .isInstanceOf(TransientDataAccessResourceException.class);

        var running = analysisRepository.findRunningRun(organizationId, review.id())
                .orElseThrow();
        assertThat(running.status()).isEqualTo(RunStatus.RUNNING);
        assertThat(running.transientFailures()).isEqualTo(1);
        assertThat(running.lastTransientReason())
                .contains("TransientDataAccessResourceException");
        assertThat(reviewRepository.find(organizationId, review.id()).orElseThrow().status())
                .isEqualTo(ReviewStatus.ANALYZING);

        var failedCheck = checks.get(1);
        var executionRow = jdbcClient.sql("""
                select status, lease_id, started_at
                from review_check_execution
                where run_id = :runId and plan_check_id = :checkId
                """)
                .param("runId", running.id())
                .param("checkId", failedCheck.id())
                .query((rs, rowNum) -> new LeaseRow(
                        rs.getString("status"),
                        rs.getObject("lease_id", UUID.class),
                        rs.getTimestamp("started_at") == null ? null
                                : rs.getTimestamp("started_at").toInstant()))
                .single();
        assertThat(executionRow.status())
                .as("the failed work rolled back and its lease was released")
                .isEqualTo("PENDING");
        assertThat(executionRow.leaseId()).isNull();
        assertThat(executionRow.startedAt()).isNull();

        var callsAfterFailure = scriptedProvider.policyComparisonCalls();
        assertThat(callsAfterFailure).isNotEmpty();
        var firstHeading = callsAfterFailure.get(0).heading();
        var decisionsAfterFailure = scriptedProvider.totalDecisions();
        assertThat(callsAfterFailure.stream()
                .filter(call -> call.heading().equals(firstHeading)).count()).isEqualTo(1);

        var diagnostics = diagnosticsService.forReview(review.id());
        assertThat(diagnostics.analysis().transientFailures()).isEqualTo(1);
        assertThat(diagnostics.analysis().lastTransientReason())
                .contains("TransientDataAccessResourceException");

        fault.disarm();
        var resumed = analysisService.analyze(organizationId, review.id());

        assertThat(resumed.id()).isEqualTo(running.id());
        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(reviewRepository.find(organizationId, review.id()).orElseThrow().status())
                .isEqualTo(ReviewStatus.COMPLETED);
        assertThat(resumed.transientFailures()).isEqualTo(1);

        var callsAfterResume = scriptedProvider.policyComparisonCalls();
        assertThat(scriptedProvider.totalDecisions()).isGreaterThan(decisionsAfterFailure);
        assertThat(callsAfterResume.stream()
                .filter(call -> call.heading().equals(firstHeading)).count())
                .as("check 1 is terminal and must not pay for its model calls twice")
                .isEqualTo(1);
        assertThat(callsAfterResume.subList(callsAfterFailure.size(), callsAfterResume.size()))
                .as("the resumed attempt re-runs the failed check, not the finished one")
                .noneMatch(call -> call.heading().equals(firstHeading));
    }

    @Test
    void aTransientFailureWhoseCounterCannotBeWrittenLeavesTheRunForJobRunr() {
        var organizationId = organizationId();
        var review = reviewWithoutPack(organizationId);
        var checks = reviewRepository.findPlanChecks(organizationId, review.id());

        fault.failOnCall(2, new TransientDataAccessResourceException("connection reset"));
        fault.failCounterWith(new TransientDataAccessResourceException("still down"));

        assertThatThrownBy(() -> analysisService.analyze(organizationId, review.id()))
                .isInstanceOf(TransientDataAccessResourceException.class);

        var running = analysisRepository.findRunningRun(organizationId, review.id())
                .orElseThrow();
        assertThat(running.status()).isEqualTo(RunStatus.RUNNING);
        assertThat(running.transientFailures())
                .as("the counter transaction failed before writing, so no count is reported")
                .isZero();
        assertThat(reviewRepository.find(organizationId, review.id()).orElseThrow().status())
                .isEqualTo(ReviewStatus.ANALYZING);

        // The counter transaction could not release the lease either, because it could not write
        // at all. Once the database is back, the ordinary 10-minute lease expiry lets the retry
        // reclaim the check; simulate that elapsed lease.
        fault.allowCounter();
        jdbcClient.sql("""
                update review_check_execution
                set started_at = now() - interval '11 minutes'
                where run_id = :runId and plan_check_id = :checkId
                """)
                .param("runId", running.id())
                .param("checkId", checks.get(1).id())
                .update();

        var resumed = analysisService.analyze(organizationId, review.id());

        assertThat(resumed.id()).isEqualTo(running.id());
        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(reviewRepository.find(organizationId, review.id()).orElseThrow().status())
                .isEqualTo(ReviewStatus.COMPLETED);
    }

    @Test
    void aTransientFailureInsideACheckBodyIsRetriedNotRecordedAsAFailedCheck() {
        var organizationId = organizationId();
        var review = reviewWithoutPack(organizationId);
        var omission = reviewRepository.findPlanChecks(organizationId, review.id()).stream()
                .filter(check -> check.mechanism() == CheckMechanism.OMISSION)
                .findFirst().orElseThrow();

        // With omissions enabled the omission check is the last check and is the first caller of
        // findExecutions inside a check body; the resumed attempt's prefetch is call 1, so call 2
        // is the check's own read.
        fault.failFindExecutionsOnCall(2,
                new TransientDataAccessResourceException("db blip inside the check"));

        assertThatThrownBy(() -> analysisService.analyze(organizationId, review.id()))
                .isInstanceOf(TransientDataAccessResourceException.class);

        var running = analysisRepository.findRunningRun(organizationId, review.id())
                .orElseThrow();
        assertThat(running.transientFailures()).isEqualTo(1);
        assertThat(reviewRepository.find(organizationId, review.id()).orElseThrow().status())
                .isEqualTo(ReviewStatus.ANALYZING);
        assertThat(analysisRepository.findExecution(
                organizationId, running.id(), omission.id()).orElseThrow().status())
                .as("a transient failure in the check body must not become a terminal FAILED check")
                .isEqualTo(CheckExecutionStatus.PENDING);

        var resumed = analysisService.analyze(organizationId, review.id());

        assertThat(resumed.id()).isEqualTo(running.id());
        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(reviewRepository.find(organizationId, review.id()).orElseThrow().status())
                .isEqualTo(ReviewStatus.COMPLETED);
    }

    @Test
    void theJobHandlerRetriesWhileAFreshLeaseBlocksTheRun() {
        var organizationId = organizationId();
        var review = reviewWithoutPack(organizationId);
        var firstCheck = reviewRepository.findPlanChecks(organizationId, review.id()).get(0);
        assertThat(reviewRepository.markAnalyzing(
                organizationId, review.id(), Instant.now())).isTrue();
        var run = ReviewAnalysisRun.running(UUID.randomUUID(), organizationId, review.id(), 1,
                RunTrigger.INITIAL, ReviewPipeline.VERSION, Instant.now());
        assertThat(analysisRepository.tryInsertRun(run)).isTrue();
        assertThat(analysisRepository.insertExecutionIfAbsent(ReviewCheckExecution.pending(
                UUID.randomUUID(), organizationId, review.id(), run.id(), firstCheck.id())))
                .isTrue();
        var execution = analysisRepository.findExecution(
                organizationId, run.id(), firstCheck.id()).orElseThrow();
        assertThat(analysisRepository.claimExecution(organizationId, execution.id()))
                .as("a crashed worker's fresh lease")
                .isPresent();

        assertThatThrownBy(() -> jobHandler.handle(organizationId, review.id()))
                .as("a job that returns while the run is RUNNING would never be retried")
                .isInstanceOf(ReviewAnalysisIncompleteException.class);
        assertThat(reviewRepository.find(organizationId, review.id()).orElseThrow().status())
                .isEqualTo(ReviewStatus.ANALYZING);

        jdbcClient.sql("""
                update review_check_execution
                set started_at = now() - interval '11 minutes'
                where id = :id
                """).param("id", execution.id()).update();

        jobHandler.handle(organizationId, review.id());

        assertThat(reviewRepository.find(organizationId, review.id()).orElseThrow().status())
                .isEqualTo(ReviewStatus.COMPLETED);
        assertThat(analysisRepository.findLatestRun(organizationId, review.id()).orElseThrow()
                .status()).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    void theRetryExhaustedFilterHealsACompletedRunInsteadOfFailingIt() {
        var organizationId = organizationId();
        var review = reviewWithoutPack(organizationId);
        assertThat(reviewRepository.markAnalyzing(
                organizationId, review.id(), Instant.now())).isTrue();
        var run = ReviewAnalysisRun.running(UUID.randomUUID(), organizationId, review.id(), 1,
                RunTrigger.INITIAL, ReviewPipeline.VERSION, Instant.now());
        assertThat(analysisRepository.tryInsertRun(run)).isTrue();
        assertThat(analysisRepository.markRunCompleted(
                organizationId, run.id(), Instant.now())).isTrue();

        retryExhaustedFilter.onFailedAfterRetries(enqueuedReviewAnalysisJob(review.id()));

        var healed = reviewRepository.find(organizationId, review.id()).orElseThrow();
        assertThat(healed.status()).isEqualTo(ReviewStatus.COMPLETED);
        assertThat(healed.failureReason()).isNull();
        assertThat(analysisRepository.findLatestRun(organizationId, review.id()).orElseThrow()
                .status()).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    void threeTransientFailuresEndTheRunAndReviewFailedAsBefore() {
        var organizationId = organizationId();
        var review = reviewWithoutPack(organizationId);
        var failedCheck = reviewRepository.findPlanChecks(organizationId, review.id()).get(0);

        for (int attempt = 1; attempt <= 2; attempt++) {
            // The same check is the first non-terminal one on every attempt, so the fault does not
            // drift to a later check as earlier checks finish.
            fault.failOnCall(1, new TransientDataAccessResourceException("db blip " + attempt));
            assertThatThrownBy(() -> analysisService.analyze(organizationId, review.id()))
                    .isInstanceOf(TransientDataAccessResourceException.class);
            var running = analysisRepository.findRunningRun(organizationId, review.id())
                    .orElseThrow();
            assertThat(running.transientFailures()).isEqualTo(attempt);
            assertThat(analysisRepository.findExecution(
                    organizationId, running.id(), failedCheck.id()).orElseThrow().status())
                    .isEqualTo(CheckExecutionStatus.PENDING);
            assertThat(reviewRepository.find(organizationId, review.id()).orElseThrow().status())
                    .isEqualTo(ReviewStatus.ANALYZING);
        }

        fault.failOnCall(1, new TransientDataAccessResourceException("db blip 3"));
        assertThatThrownBy(() -> analysisService.analyze(organizationId, review.id()))
                .isInstanceOf(TransientDataAccessResourceException.class);

        var failedRun = analysisRepository.findLatestRun(organizationId, review.id())
                .orElseThrow();
        assertThat(failedRun.status()).isEqualTo(RunStatus.FAILED);
        assertThat(failedRun.transientFailures()).isEqualTo(3);
        assertThat(failedRun.failureReason()).contains("TransientDataAccessResourceException");
        assertThat(failedRun.completedAt()).isNotNull();
        var failedReview = reviewRepository.find(organizationId, review.id()).orElseThrow();
        assertThat(failedReview.status()).isEqualTo(ReviewStatus.FAILED);
        assertThat(failedReview.failureReason()).contains("TransientDataAccessResourceException");
    }

    @Test
    void aNonTransientFailureIsStillTerminalOnItsFirstOccurrence() {
        var organizationId = organizationId();
        var review = reviewWithoutPack(organizationId);

        fault.failOnCall(2, new IllegalStateException("write rejected"));

        assertThatThrownBy(() -> analysisService.analyze(organizationId, review.id()))
                .isInstanceOf(IllegalStateException.class);

        var failedRun = analysisRepository.findLatestRun(organizationId, review.id())
                .orElseThrow();
        assertThat(failedRun.status()).isEqualTo(RunStatus.FAILED);
        assertThat(failedRun.transientFailures()).isZero();
        assertThat(failedRun.failureReason()).contains("IllegalStateException");
        assertThat(reviewRepository.find(organizationId, review.id()).orElseThrow().status())
                .isEqualTo(ReviewStatus.FAILED);
    }

    @Test
    void aResumedRunOlderThanTheBudgetStillRunsItsRemainingChecks() {
        var organizationId = organizationId();
        var review = reviewWithoutPack(organizationId);
        fault.failOnCall(2, new TransientDataAccessResourceException("db blip"));
        assertThatThrownBy(() -> analysisService.analyze(organizationId, review.id()))
                .isInstanceOf(TransientDataAccessResourceException.class);

        var running = analysisRepository.findRunningRun(organizationId, review.id())
                .orElseThrow();
        jdbcClient.sql("update review_analysis_run set started_at = :startedAt where id = :id")
                .param("startedAt", OffsetDateTime.ofInstant(
                        Instant.now().minus(Duration.ofMinutes(31)), ZoneOffset.UTC))
                .param("id", running.id())
                .update();
        fault.disarm();

        var resumed = analysisService.analyze(organizationId, review.id());

        assertThat(resumed.id()).isEqualTo(running.id());
        assertThat(resumed.status()).isEqualTo(RunStatus.COMPLETED);
        var executions = analysisRepository.findExecutions(organizationId, resumed.id());
        assertThat(executions).allSatisfy(execution ->
                assertThat(execution.detail().get("degradedReason"))
                        .isNotEqualTo("review_deadline_exceeded"));
        assertThat(executions).anySatisfy(execution ->
                assertThat(execution.status()).isEqualTo(CheckExecutionStatus.COMPLETED));
        assertThat(reviewRepository.find(organizationId, review.id()).orElseThrow().status())
                .isEqualTo(ReviewStatus.COMPLETED);
    }

    @Test
    void aNewRunDefaultsToZeroTransientFailures() {
        var organizationId = organizationId();
        var review = reviewWithoutPack(organizationId);
        assertThat(reviewRepository.markAnalyzing(
                organizationId, review.id(), Instant.now())).isTrue();
        var run = ReviewAnalysisRun.running(UUID.randomUUID(), organizationId, review.id(), 1,
                RunTrigger.INITIAL, ReviewPipeline.VERSION, Instant.now());
        assertThat(analysisRepository.tryInsertRun(run)).isTrue();

        var stored = analysisRepository.findRunningRun(organizationId, review.id())
                .orElseThrow();
        assertThat(stored.transientFailures()).isZero();
        assertThat(stored.lastTransientReason()).isNull();
    }

    @Test
    void theRetryExhaustedFilterFailsOnlyReviewAnalysisJobsAndOnlyOnce() {
        var organizationId = organizationId();
        var review = reviewWithoutPack(organizationId);
        assertThat(reviewRepository.markAnalyzing(
                organizationId, review.id(), Instant.now())).isTrue();
        var run = ReviewAnalysisRun.running(UUID.randomUUID(), organizationId, review.id(), 1,
                RunTrigger.INITIAL, ReviewPipeline.VERSION, Instant.now());
        assertThat(analysisRepository.tryInsertRun(run)).isTrue();

        retryExhaustedFilter.onFailedAfterRetries(job(
                "com.rivell.workbench.platform.jobs.OtherJobHandler", "handle",
                organizationId, review.id()));
        assertThat(analysisRepository.findRunningRun(organizationId, review.id()))
                .as("a filter for review analysis must not touch another job")
                .isPresent();
        assertThat(reviewRepository.find(organizationId, review.id()).orElseThrow().status())
                .isEqualTo(ReviewStatus.ANALYZING);

        // The production enqueue path, not a hand-built JobDetails: the filter must recognise the
        // class/method/parameters JobRunr actually stored for this review.
        retryExhaustedFilter.onFailedAfterRetries(enqueuedReviewAnalysisJob(review.id()));

        var failedRun = analysisRepository.findLatestRun(organizationId, review.id())
                .orElseThrow();
        assertThat(failedRun.status()).isEqualTo(RunStatus.FAILED);
        assertThat(failedRun.failureReason()).isEqualTo(EXHAUSTED_REASON);
        var failedReview = reviewRepository.find(organizationId, review.id()).orElseThrow();
        assertThat(failedReview.status()).isEqualTo(ReviewStatus.FAILED);
        assertThat(failedReview.failureReason()).isEqualTo(EXHAUSTED_REASON);
        var completedAt = failedReview.completedAt();
        var runCompletedAt = failedRun.completedAt();

        retryExhaustedFilter.onFailedAfterRetries(enqueuedReviewAnalysisJob(review.id()));

        assertThat(reviewRepository.find(organizationId, review.id()).orElseThrow().completedAt())
                .isEqualTo(completedAt);
        assertThat(analysisRepository.findLatestRun(organizationId, review.id())
                .orElseThrow().completedAt()).isEqualTo(runCompletedAt);
    }

    private org.jobrunr.jobs.Job enqueuedReviewAnalysisJob(UUID reviewId) {
        return storageProvider.getJobList(
                        org.jobrunr.jobs.states.StateName.ENQUEUED,
                        new org.jobrunr.storage.navigation.AmountRequest("updatedAt:DESC", 100))
                .stream()
                .filter(candidate -> java.util.Arrays.stream(
                                candidate.getJobDetails().getJobParameterValues())
                        .anyMatch(reviewId::equals))
                .findFirst()
                .orElseThrow();
    }

    private static org.jobrunr.jobs.Job job(
            String className, String methodName, Object... arguments) {
        var parameters = java.util.Arrays.stream(arguments)
                .map(argument -> new org.jobrunr.jobs.JobParameter(
                        argument.getClass(), argument))
                .toList();
        return new org.jobrunr.jobs.Job(new org.jobrunr.jobs.JobDetails(
                className, null, methodName, parameters));
    }
}
