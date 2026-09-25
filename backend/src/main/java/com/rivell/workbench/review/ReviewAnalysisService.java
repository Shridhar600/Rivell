package com.rivell.workbench.review;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.transaction.support.TransactionTemplate;

import com.rivell.workbench.contracts.ContractEnrichmentService;
import com.rivell.workbench.contracts.ContractSemantics;
import com.rivell.workbench.intelligence.EmbeddingService;
import com.rivell.workbench.retrieval.RetrievalProperties;
import com.rivell.workbench.review.check.AnalysisEnvironment;
import com.rivell.workbench.review.check.CheckOutcome;
import com.rivell.workbench.review.check.ReviewCheckContext;
import com.rivell.workbench.review.check.ReviewCheckExecutors;

/**
 * Deterministic machine-analysis orchestration. One run (generation) per attempt with a
 * lease-based check claim; terminal checks are skipped on retry; a check's signals and
 * its terminal state commit atomically; review and run complete in one transaction.
 */
public class ReviewAnalysisService {

    private final ReviewRepository reviewRepository;
    private final ReviewAnalysisRepository analysisRepository;
    private final ReviewCheckExecutors executors;
    private final ContractEnrichmentService contractEnrichmentService;
    private final RetrievalProperties retrievalProperties;
    private final EmbeddingService embeddingService;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public ReviewAnalysisService(
            ReviewRepository reviewRepository,
            ReviewAnalysisRepository analysisRepository,
            ReviewCheckExecutors executors,
            ContractEnrichmentService contractEnrichmentService,
            RetrievalProperties retrievalProperties,
            EmbeddingService embeddingService,
            TransactionTemplate transactionTemplate,
            Clock clock) {
        this.reviewRepository = reviewRepository;
        this.analysisRepository = analysisRepository;
        this.executors = executors;
        this.contractEnrichmentService = contractEnrichmentService;
        this.retrievalProperties = retrievalProperties;
        this.embeddingService = embeddingService;
        this.transactionTemplate = transactionTemplate;
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
        ReviewAnalysisRun acquiredRun = null;
        try {
            var snapshot = reviewRepository.findSnapshot(organizationId, reviewId).orElseThrow(
                    () -> new IllegalStateException("Review snapshot missing: " + reviewId));
            acquiredRun = resolveRun(organizationId, review, reviewId);
            if (reviewRepository.find(organizationId, reviewId).orElseThrow().status()
                    == ReviewStatus.COMPLETED) {
                return acquiredRun;
            }
            reviewRepository.markAnalyzing(organizationId, reviewId, clock.instant());

            var run = acquiredRun;
            var semantics = contractEnrichmentService.getSemanticsForOrganization(
                    organizationId, snapshot.contractVersionId());
            var environment = evaluateEnvironment(snapshot, semantics);
            executePlannedChecks(review, snapshot, run, semantics, environment);
            completeIfDone(organizationId, reviewId, run);
        } catch (RuntimeException e) {
            var failedRun = acquiredRun;
            transactionTemplate.executeWithoutResult(status -> {
                if (failedRun != null) {
                    analysisRepository.markRunFailed(organizationId, failedRun.id(),
                            failureReason(e), clock.instant());
                }
                reviewRepository.markFailed(organizationId, reviewId,
                        failureReason(e), clock.instant());
            });
            throw e;
        }
        return analysisRepository.findLatestRun(organizationId, reviewId)
                .orElse(acquiredRun);
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
        var pending = Math.max(0, planned - (int) executions.stream()
                .filter(execution -> execution.status().terminal()).count());
        return new ReviewProgress(
                review.status(), planned, pending,
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

    private ReviewAnalysisRun resolveRun(
            UUID organizationId, Review review, UUID reviewId) {
        for (int attempt = 0; attempt < 2; attempt++) {
            var running = analysisRepository.findRunningRun(organizationId, reviewId);
            if (running.isPresent()) {
                return running.get();
            }
            var latest = analysisRepository.findLatestRun(organizationId, reviewId);
            if (latest.isPresent() && latest.get().status() == RunStatus.COMPLETED) {
                reviewRepository.markCompleted(organizationId, reviewId, clock.instant());
                return latest.get();
            }
            var next = latest.map(run -> run.runNumber() + 1).orElse(1);
            var created = ReviewAnalysisRun.running(UUID.randomUUID(), organizationId,
                    reviewId, next, RunTrigger.INITIAL, ReviewPipeline.VERSION,
                    clock.instant());
            if (analysisRepository.tryInsertRun(created)) {
                return created;
            }
        }
        return analysisRepository.findRunningRun(organizationId, reviewId)
                .orElseGet(() -> analysisRepository.findLatestRun(organizationId, reviewId)
                        .orElseThrow(() -> new IllegalStateException(
                                "Unable to claim an analysis run for review " + reviewId)));
    }

    private void executePlannedChecks(
            Review review, ReviewSnapshot snapshot, ReviewAnalysisRun run,
            ContractSemantics semantics, AnalysisEnvironment environment) {
        for (var check : reviewRepository.findPlanChecks(review.organizationId(), review.id())) {
            var existing = analysisRepository.findExecution(
                    review.organizationId(), run.id(), check.id());
            if (existing.isPresent() && existing.get().status().terminal()) {
                continue;
            }
            if (existing.isEmpty()) {
                analysisRepository.insertExecutionIfAbsent(ReviewCheckExecution.pending(
                        UUID.randomUUID(), review.organizationId(), review.id(),
                        run.id(), check.id()));
            }
            var execution = analysisRepository.findExecution(
                    review.organizationId(), run.id(), check.id()).orElseThrow();
            if (!analysisRepository.claimExecution(review.organizationId(), execution.id())) {
                continue;
            }
            var context = new ReviewCheckContext(review, snapshot, check, run.id(),
                    semantics, environment, clock.instant());
            var outcome = runCheck(check, context, environment);
            transactionTemplate.executeWithoutResult(status -> {
                analysisRepository.deleteSignals(
                        review.organizationId(), run.id(), check.id());
                analysisRepository.insertSignals(outcome.signals());
                analysisRepository.completeExecution(review.organizationId(), execution.id(),
                        outcome.status(), outcome.evidenceTraceId(), outcome.detail(),
                        outcome.signals().size(), clock.instant());
            });
        }
    }

    private void completeIfDone(UUID organizationId, UUID reviewId, ReviewAnalysisRun run) {
        var checks = reviewRepository.findPlanChecks(organizationId, reviewId);
        var executions = analysisRepository.findExecutions(organizationId, run.id());
        if (executions.size() == checks.size()
                && executions.stream().allMatch(execution -> execution.status().terminal())) {
            transactionTemplate.executeWithoutResult(status -> {
                if (analysisRepository.markRunCompleted(organizationId, run.id(), clock.instant())) {
                    reviewRepository.markCompleted(organizationId, reviewId, clock.instant());
                }
            });
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
        } catch (com.rivell.workbench.intelligence.SemanticDecisionException e) {
            return CheckOutcome.degraded("provider_unavailable:" + e.getClass().getSimpleName());
        } catch (RuntimeException e) {
            return CheckOutcome.failed(failureReason(e));
        }
    }

    static String failureReason(RuntimeException e) {
        var origin = e.getStackTrace().length == 0 ? "unknown"
                : e.getStackTrace()[0].getClassName() + "."
                        + e.getStackTrace()[0].getMethodName()
                        + ":" + e.getStackTrace()[0].getLineNumber();
        var message = e.getMessage() == null ? "" : ":" + e.getMessage();
        return e.getClass().getSimpleName() + message + "@" + origin;
    }

    private static int count(
            java.util.List<ReviewCheckExecution> executions, CheckExecutionStatus status) {
        return (int) executions.stream().filter(e -> e.status() == status).count();
    }
}
