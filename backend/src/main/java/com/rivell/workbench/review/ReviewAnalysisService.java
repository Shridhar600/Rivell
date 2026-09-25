package com.rivell.workbench.review;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.transaction.support.TransactionTemplate;

import com.rivell.workbench.contracts.ContractEnrichmentService;
import com.rivell.workbench.contracts.ContractSemantics;
import com.rivell.workbench.intelligence.EmbeddingService;
import com.rivell.workbench.intelligence.ModelBindingCatalog;
import com.rivell.workbench.retrieval.EvidenceService;
import com.rivell.workbench.retrieval.RetrievalProperties;
import com.rivell.workbench.review.check.AnalysisEnvironment;
import com.rivell.workbench.review.check.CheckOutcome;
import com.rivell.workbench.review.check.ReviewCheckContext;
import com.rivell.workbench.review.check.ReviewCheckExecutors;
import com.rivell.workbench.review.check.ReviewEvidenceCache;

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
    private final EvidenceService evidenceService;
    private final ModelBindingCatalog modelBindingCatalog;
    private final FindingRepository findingRepository;
    private final FindingAssembler findingAssembler;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public ReviewAnalysisService(
            ReviewRepository reviewRepository,
            ReviewAnalysisRepository analysisRepository,
            ReviewCheckExecutors executors,
            ContractEnrichmentService contractEnrichmentService,
            RetrievalProperties retrievalProperties,
            EmbeddingService embeddingService,
            EvidenceService evidenceService,
            ModelBindingCatalog modelBindingCatalog,
            FindingRepository findingRepository,
            FindingAssembler findingAssembler,
            TransactionTemplate transactionTemplate,
            Clock clock) {
        this.reviewRepository = reviewRepository;
        this.analysisRepository = analysisRepository;
        this.executors = executors;
        this.contractEnrichmentService = contractEnrichmentService;
        this.retrievalProperties = retrievalProperties;
        this.embeddingService = embeddingService;
        this.evidenceService = evidenceService;
        this.modelBindingCatalog = modelBindingCatalog;
        this.findingRepository = findingRepository;
        this.findingAssembler = findingAssembler;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    public ReviewAnalysisRun analyze(UUID organizationId, UUID reviewId) {
        var review = reviewRepository.find(organizationId, reviewId)
                .orElseThrow(() -> new ReviewNotFoundException(reviewId));
        if (review.status() == ReviewStatus.COMPLETED || review.status() == ReviewStatus.FAILED) {
            return activeOrLatestRun(organizationId, reviewId);
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
        var fallback = acquiredRun;
        return analysisRepository.findLatestCompletedRun(organizationId, reviewId)
                .orElseGet(() -> fallback == null
                        ? activeOrLatestRun(organizationId, reviewId) : fallback);
    }

    private ReviewAnalysisRun activeOrLatestRun(UUID organizationId, UUID reviewId) {
        return analysisRepository.findLatestCompletedRun(organizationId, reviewId)
                .or(() -> analysisRepository.findLatestRun(organizationId, reviewId))
                .orElseThrow(() -> new IllegalStateException(
                        "Terminal review has no analysis run: " + reviewId));
    }

    /**
     * Recomputes a completed review as a new run (never a status rollback): the
     * previous run and its signals remain as audit history, the new run becomes the
     * active generation when it completes. Failed reviews stay terminal in v1.
     */
    public ReviewAnalysisRun recompute(UUID organizationId, UUID reviewId) {
        var review = reviewRepository.find(organizationId, reviewId)
                .orElseThrow(() -> new ReviewNotFoundException(reviewId));
        if (review.status() != ReviewStatus.COMPLETED) {
            throw new IllegalStateException(
                    "recompute requires a COMPLETED review: " + reviewId);
        }
        ReviewAnalysisRun acquiredRun = null;
        try {
            var snapshot = reviewRepository.findSnapshot(organizationId, reviewId).orElseThrow(
                    () -> new IllegalStateException("Review snapshot missing: " + reviewId));
            acquiredRun = resolveRecomputeRun(organizationId, reviewId);
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
            });
            throw e;
        }
        var fallback = acquiredRun;
        return analysisRepository.findLatestCompletedRun(organizationId, reviewId)
                .orElseGet(() -> fallback == null
                        ? activeOrLatestRun(organizationId, reviewId) : fallback);
    }

    private ReviewAnalysisRun resolveRecomputeRun(UUID organizationId, UUID reviewId) {
        var running = analysisRepository.findRunningRun(organizationId, reviewId);
        if (running.isPresent()) {
            return running.get();
        }
        var created = ReviewAnalysisRun.running(UUID.randomUUID(), organizationId,
                reviewId, analysisRepository.maxRunNumber(organizationId, reviewId) + 1,
                RunTrigger.RECOMPUTE, ReviewPipeline.VERSION, clock.instant());
        if (analysisRepository.tryInsertRun(created)) {
            return created;
        }
        return analysisRepository.findRunningRun(organizationId, reviewId)
                .or(() -> analysisRepository.findLatestRun(organizationId, reviewId)
                        .filter(candidate -> candidate.status() == RunStatus.COMPLETED))
                .orElseThrow(() -> new IllegalStateException(
                        "Unable to claim a recompute run for review " + reviewId));
    }

    public ReviewProgress progress(UUID organizationId, UUID reviewId) {
        var review = reviewRepository.find(organizationId, reviewId)
                .orElseThrow(() -> new ReviewNotFoundException(reviewId));
        var planned = reviewRepository.findPlanChecks(organizationId, reviewId).size();
        var latest = analysisRepository.findLatestRun(organizationId, reviewId);
        var countedRun = analysisRepository.findLatestCompletedRun(organizationId, reviewId)
                .or(() -> latest);
        if (countedRun.isEmpty()) {
            return new ReviewProgress(review.status(), planned, planned, 0, 0, 0, 0, 0, 0, 0,
                    null);
        }
        var executions = analysisRepository.findExecutions(organizationId, countedRun.get().id());
        var pending = Math.max(0, planned - executions.size())
                + count(executions, CheckExecutionStatus.PENDING);
        return new ReviewProgress(
                review.status(), planned, pending,
                count(executions, CheckExecutionStatus.RUNNING),
                count(executions, CheckExecutionStatus.COMPLETED),
                count(executions, CheckExecutionStatus.DEGRADED),
                count(executions, CheckExecutionStatus.FAILED),
                count(executions, CheckExecutionStatus.SKIPPED),
                analysisRepository.countSignals(organizationId, countedRun.get().id()),
                findingRepository.countFindings(organizationId, countedRun.get().id()),
                latest.map(ReviewAnalysisRun::status).orElse(null));
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
        var frozen = snapshot.modelPolicy();
        var live = modelBindingCatalog.current();
        var bindingsMatch = frozen.decisionProviderId()
                        .equals(live.decisionProvider().providerId())
                && frozen.decisionModelAlias().equals(live.decisionProvider().modelAlias())
                && frozen.generativeProviderId()
                        .equals(live.generativeProvider().providerId())
                && frozen.generativeModelAlias()
                        .equals(live.generativeProvider().modelAlias());
        return new AnalysisEnvironment(digestMatches, indexMatches, modelMatches,
                bindingsMatch);
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
        var evidenceCache = new ReviewEvidenceCache(evidenceService);
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
            var lease = analysisRepository.claimExecution(
                    review.organizationId(), execution.id());
            if (lease.isEmpty()) {
                continue;
            }
            var context = new ReviewCheckContext(review, snapshot, check, run.id(),
                    semantics, environment, evidenceCache, clock.instant());
            var outcome = runCheck(check, context, environment);
            var leaseId = lease.get();
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    if (!analysisRepository.completeExecution(review.organizationId(),
                            execution.id(), leaseId, outcome.status(), outcome.evidenceTraceId(),
                            outcome.detail(), outcome.signals().size(), clock.instant())) {
                        throw new LeaseLostException(execution.id());
                    }
                    findingRepository.deleteFindings(review.organizationId(), run.id());
                    analysisRepository.deleteSignals(
                            review.organizationId(), run.id(), check.id());
                    analysisRepository.insertSignals(outcome.signals());
                });
            } catch (LeaseLostException e) {
                // another worker reclaimed this execution; it owns the terminal write
                continue;
            }
        }
    }

    private void completeIfDone(UUID organizationId, UUID reviewId, ReviewAnalysisRun run) {
        var checks = reviewRepository.findPlanChecks(organizationId, reviewId);
        var executions = analysisRepository.findExecutions(organizationId, run.id());
        if (executions.size() == checks.size()
                && executions.stream().allMatch(execution -> execution.status().terminal())) {
            // assemble before the completion transaction: an assembly failure fails this
            // generation terminally instead of leaving a wedged RUNNING run
            var assembled = findingAssembler.assemble(run,
                    analysisRepository.findSignals(organizationId, run.id()), clock.instant());
            transactionTemplate.executeWithoutResult(status -> {
                if (analysisRepository.markRunCompleted(organizationId, run.id(), clock.instant())) {
                    findingRepository.replaceFindings(organizationId, reviewId, run.id(),
                            assembled);
                    reviewRepository.markCompleted(organizationId, reviewId, clock.instant());
                }
            });
        }
    }

    private CheckOutcome runCheck(
            ReviewPlanCheck check, ReviewCheckContext context, AnalysisEnvironment environment) {
        if (!environment.fullyMatches()) {
            return CheckOutcome.degraded(environment.mismatchReason());
        }
        try {
            return executors.find(check.mechanism())
                    .map(executor -> executor.execute(context))
                    .orElseGet(() -> CheckOutcome.degraded(
                            "mechanism_not_available:" + check.mechanism()));
        } catch (com.rivell.workbench.intelligence.SemanticDecisionException e) {
            return isDecisionContractError(e)
                    ? CheckOutcome.failed("decision_contract_error:" + e.getClass().getSimpleName())
                    : CheckOutcome.degraded(
                            "provider_unavailable:" + e.getClass().getSimpleName());
        } catch (RuntimeException e) {
            return CheckOutcome.failed(failureReason(e));
        }
    }

    private static boolean isDecisionContractError(
            com.rivell.workbench.intelligence.SemanticDecisionException e) {
        return e instanceof com.rivell.workbench.intelligence.SpecStateMismatchException
                || e instanceof com.rivell.workbench.intelligence.UnknownDecisionSpecException
                || e instanceof com.rivell.workbench.intelligence.NoProviderConfiguredException;
    }

    /** Signals that the check's lease was reclaimed by another worker. */
    static final class LeaseLostException extends RuntimeException {
        LeaseLostException(java.util.UUID executionId) {
            super("execution lease lost: " + executionId);
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
