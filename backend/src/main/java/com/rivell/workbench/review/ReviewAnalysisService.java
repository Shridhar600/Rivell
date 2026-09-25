package com.rivell.workbench.review;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.rivell.workbench.contracts.ContractEnrichmentService;
import com.rivell.workbench.contracts.ContractSemantics;
import com.rivell.workbench.intelligence.EmbeddingService;
import com.rivell.workbench.retrieval.RetrievalProperties;
import com.rivell.workbench.review.check.AnalysisEnvironment;
import com.rivell.workbench.review.check.CheckOutcome;
import com.rivell.workbench.review.check.ReviewCheckContext;
import com.rivell.workbench.review.check.ReviewCheckExecutors;

/**
 * Deterministic machine-analysis orchestration: one run (generation) per attempt,
 * terminal checks are skipped on retry, signals are dedupe-keyed per run, and the
 * review completes even when checks degrade (uncertainty, not failure).
 */
public class ReviewAnalysisService {

    private final ReviewRepository reviewRepository;
    private final ReviewAnalysisRepository analysisRepository;
    private final ReviewCheckExecutors executors;
    private final ContractEnrichmentService contractEnrichmentService;
    private final RetrievalProperties retrievalProperties;
    private final EmbeddingService embeddingService;
    private final Clock clock;

    public ReviewAnalysisService(
            ReviewRepository reviewRepository,
            ReviewAnalysisRepository analysisRepository,
            ReviewCheckExecutors executors,
            ContractEnrichmentService contractEnrichmentService,
            RetrievalProperties retrievalProperties,
            EmbeddingService embeddingService,
            Clock clock) {
        this.reviewRepository = reviewRepository;
        this.analysisRepository = analysisRepository;
        this.executors = executors;
        this.contractEnrichmentService = contractEnrichmentService;
        this.retrievalProperties = retrievalProperties;
        this.embeddingService = embeddingService;
        this.clock = clock;
    }

    public ReviewAnalysisRun analyze(UUID organizationId, UUID reviewId) {
        var review = reviewRepository.find(organizationId, reviewId)
                .orElseThrow(() -> new ReviewNotFoundException(reviewId));
        if (review.status() == ReviewStatus.COMPLETED || review.status() == ReviewStatus.FAILED) {
            return analysisRepository.findLatestRun(organizationId, reviewId).orElseThrow(
                    () -> new IllegalStateException(
                            "Terminal review has no analysis run: " + reviewId));
        }
        var snapshot = reviewRepository.findSnapshot(organizationId, reviewId).orElseThrow(
                () -> new IllegalStateException("Review snapshot missing: " + reviewId));
        var run = analysisRepository.findRunningRun(organizationId, reviewId)
                .orElseGet(() -> startRun(review));
        if (review.status() == ReviewStatus.ANALYSIS_QUEUED) {
            reviewRepository.markAnalyzing(organizationId, reviewId, clock.instant());
        }

        var semantics = contractEnrichmentService.getSemanticsForOrganization(
                organizationId, snapshot.contractVersionId());
        var environment = evaluateEnvironment(snapshot, semantics);
        executePlannedChecks(review, snapshot, run, semantics, environment);

        var checks = reviewRepository.findPlanChecks(organizationId, reviewId);
        var executions = analysisRepository.findExecutions(organizationId, run.id());
        if (executions.size() == checks.size()
                && executions.stream().allMatch(execution -> execution.status().terminal())) {
            analysisRepository.markRunCompleted(organizationId, run.id(), clock.instant());
            reviewRepository.markCompleted(organizationId, reviewId, clock.instant());
        }
        return analysisRepository.findLatestRun(organizationId, reviewId).orElse(run);
    }

    public ReviewProgress progress(UUID organizationId, UUID reviewId) {
        var review = reviewRepository.find(organizationId, reviewId)
                .orElseThrow(() -> new ReviewNotFoundException(reviewId));
        var planned = reviewRepository.findPlanChecks(organizationId, reviewId).size();
        var latest = analysisRepository.findLatestRun(organizationId, reviewId);
        if (latest.isEmpty()) {
            return new ReviewProgress(review.status(), planned, planned, 0, 0, 0, 0, 0, 0);
        }
        var executions = analysisRepository.findExecutions(organizationId, latest.get().id());
        return new ReviewProgress(
                review.status(), planned,
                count(executions, CheckExecutionStatus.PENDING),
                count(executions, CheckExecutionStatus.RUNNING),
                count(executions, CheckExecutionStatus.COMPLETED),
                count(executions, CheckExecutionStatus.DEGRADED),
                count(executions, CheckExecutionStatus.FAILED),
                count(executions, CheckExecutionStatus.SKIPPED),
                analysisRepository.countSignals(organizationId, latest.get().id()));
    }

    public AnalysisEnvironment evaluateEnvironment(
            ReviewSnapshot snapshot, ContractSemantics semantics) {
        var digestMatches = SemanticsDigest.of(semantics).equals(snapshot.semanticsDigest());
        var indexMatches = snapshot.embeddingIndexVersion()
                .equals(retrievalProperties.indexVersion());
        var liveIdentity = embeddingService.isConfigured()
                ? embeddingService.modelIdentity()
                : null;
        var modelMatches = Objects.equals(snapshot.embeddingModelIdentity(), liveIdentity);
        return new AnalysisEnvironment(digestMatches, indexMatches, modelMatches);
    }

    private ReviewAnalysisRun startRun(Review review) {
        var runNumber = analysisRepository.maxRunNumber(
                review.organizationId(), review.id()) + 1;
        var run = ReviewAnalysisRun.running(UUID.randomUUID(), review.organizationId(),
                review.id(), runNumber, RunTrigger.INITIAL, ReviewPipeline.VERSION,
                clock.instant());
        analysisRepository.insertRun(run);
        return run;
    }

    private void executePlannedChecks(
            Review review, ReviewSnapshot snapshot, ReviewAnalysisRun run,
            ContractSemantics semantics, AnalysisEnvironment environment) {
        for (var check : reviewRepository.findPlanChecks(
                review.organizationId(), review.id())) {
            var existing = analysisRepository.findExecution(
                    review.organizationId(), run.id(), check.id());
            if (existing.isPresent() && existing.get().status().terminal()) {
                continue;
            }
            var execution = existing.orElseGet(() -> {
                analysisRepository.insertExecutionIfAbsent(ReviewCheckExecution.pending(
                        UUID.randomUUID(), review.organizationId(), review.id(),
                        run.id(), check.id()));
                return analysisRepository.findExecution(
                        review.organizationId(), run.id(), check.id()).orElseThrow();
            });
            analysisRepository.updateExecution(review.organizationId(), execution.id(),
                    CheckExecutionStatus.RUNNING, null, Map.of(), 0, clock.instant(), null);

            var context = new ReviewCheckContext(review, snapshot, check, run.id(),
                    semantics, environment, clock.instant());
            var outcome = runCheck(check, context, environment);
            analysisRepository.insertSignals(outcome.signals());
            var signalCount = analysisRepository.countSignals(
                    review.organizationId(), run.id(), check.id());
            analysisRepository.updateExecution(review.organizationId(), execution.id(),
                    outcome.status(), null, outcome.detail(), signalCount, null,
                    clock.instant());
        }
    }

    private CheckOutcome runCheck(
            ReviewPlanCheck check, ReviewCheckContext context, AnalysisEnvironment environment) {
        if (!environment.semanticsDigestMatches()) {
            return CheckOutcome.degraded(environment.mismatchReason());
        }
        try {
            return executors.find(check.mechanism())
                    .map(executor -> executor.execute(context))
                    .orElseGet(() -> CheckOutcome.degraded(
                            "mechanism_not_available:" + check.mechanism()));
        } catch (RuntimeException e) {
            return CheckOutcome.failed(e.getClass().getSimpleName());
        }
    }

    private static int count(
            java.util.List<ReviewCheckExecution> executions, CheckExecutionStatus status) {
        return (int) executions.stream().filter(e -> e.status() == status).count();
    }
}
