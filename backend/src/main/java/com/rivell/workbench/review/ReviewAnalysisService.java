package com.rivell.workbench.review;

import java.sql.SQLTransientException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.rivell.workbench.contracts.ContractEnrichmentService;
import com.rivell.workbench.contracts.ContractQueryService;
import com.rivell.workbench.contracts.ContractSemantics;
import com.rivell.workbench.contracts.SemanticsDigest;
import com.rivell.workbench.intelligence.ClauseEmbeddingCache;
import com.rivell.workbench.intelligence.EmbeddingService;
import com.rivell.workbench.intelligence.ModelBindingCatalog;
import com.rivell.workbench.intelligence.ProviderFailures;
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
 *
 * <p>A transient infrastructure failure (a dropped connection, a statement timeout, a reclaimed
 * lease) does not fail the run: it is counted in a separate transaction, the failed check's lease
 * is released, and the exception is rethrown so JobRunr retries the same run. Only when the run's
 * counter reaches its limit, or the cause is something a retry cannot fix, does the run become
 * terminal.
 */
public class ReviewAnalysisService {

    /** Code-owned reason for a check the analysis budget left unrun. */
    public static final String DEADLINE_DEGRADED_REASON = "review_deadline_exceeded";

    private static final Logger LOG = LoggerFactory.getLogger(ReviewAnalysisService.class);

    private final ReviewRepository reviewRepository;
    private final ReviewAnalysisRepository analysisRepository;
    private final ReviewCheckExecutors executors;
    private final ContractEnrichmentService contractEnrichmentService;
    private final ContractQueryService contractQueryService;
    private final RetrievalProperties retrievalProperties;
    private final EmbeddingService embeddingService;
    private final EvidenceService evidenceService;
    private final ModelBindingCatalog modelBindingCatalog;
    private final FindingRepository findingRepository;
    private final FindingAssembler findingAssembler;
    private final PlaybookCoverageBuilder playbookCoverageBuilder;
    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate failureTransactionTemplate;
    private final Clock clock;
    private final Duration analysisBudget;
    private final int maxTransientFailures;

    public ReviewAnalysisService(
            ReviewRepository reviewRepository,
            ReviewAnalysisRepository analysisRepository,
            ReviewCheckExecutors executors,
            ContractEnrichmentService contractEnrichmentService,
            ContractQueryService contractQueryService,
            RetrievalProperties retrievalProperties,
            EmbeddingService embeddingService,
            EvidenceService evidenceService,
            ModelBindingCatalog modelBindingCatalog,
            FindingRepository findingRepository,
            FindingAssembler findingAssembler,
            PlaybookCoverageBuilder playbookCoverageBuilder,
            TransactionTemplate transactionTemplate,
            PlatformTransactionManager transactionManager,
            Clock clock,
            Duration analysisBudget,
            int maxTransientFailures) {
        this.reviewRepository = reviewRepository;
        this.analysisRepository = analysisRepository;
        this.executors = executors;
        this.contractEnrichmentService = contractEnrichmentService;
        this.contractQueryService = contractQueryService;
        this.retrievalProperties = retrievalProperties;
        this.embeddingService = embeddingService;
        this.evidenceService = evidenceService;
        this.modelBindingCatalog = modelBindingCatalog;
        this.findingRepository = findingRepository;
        this.findingAssembler = findingAssembler;
        this.playbookCoverageBuilder = playbookCoverageBuilder;
        this.transactionTemplate = transactionTemplate;
        this.failureTransactionTemplate = new TransactionTemplate(transactionManager);
        this.failureTransactionTemplate.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
        this.analysisBudget = analysisBudget == null ? Duration.ofMinutes(30) : analysisBudget;
        this.maxTransientFailures = Math.max(0, maxTransientFailures);
    }

    public ReviewAnalysisRun analyze(UUID organizationId, UUID reviewId) {
        var review = reviewRepository.find(organizationId, reviewId)
                .orElseThrow(() -> new ReviewNotFoundException(reviewId));
        if (review.status() == ReviewStatus.COMPLETED || review.status() == ReviewStatus.FAILED) {
            return activeOrLatestRun(organizationId, reviewId);
        }
        ReviewAnalysisRun acquiredRun = null;
        var attempt = new Attempt();
        var attemptStartedAt = clock.instant();
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
            executePlannedChecks(review, snapshot, run, semantics, environment, attempt,
                    attemptStartedAt);
            completeIfDone(organizationId, reviewId, run);
        } catch (RuntimeException e) {
            handleFailure(organizationId, reviewId, acquiredRun, attempt, e, true);
            throw e;
        }
        var fallback = acquiredRun;
        return analysisRepository.findLatestCompletedRun(organizationId, reviewId)
                .orElseGet(() -> fallback == null
                        ? activeOrLatestRun(organizationId, reviewId) : fallback);
    }

    /**
     * A failure that infrastructure will probably not repeat (a dropped connection, a statement
     * timeout, a reclaimed lease) is counted against the RUNNING run and rethrown so JobRunr retries;
     * the next attempt resumes the same run. A failure that will repeat stays terminal on the first
     * occurrence, exactly as before. If the counter itself cannot be written the database is the
     * problem, so the original failure is rethrown untouched and JobRunr's retries decide.
     */
    private void handleFailure(UUID organizationId, UUID reviewId, ReviewAnalysisRun run,
            Attempt attempt, RuntimeException failure, boolean failReview) {
        if (isTransientFailure(failure)) {
            if (run != null) {
                recordTransientFailure(organizationId, reviewId, run, attempt, failure, failReview);
            } else {
                // No run to count against (the failure happened before it was acquired). Failing
                // the review here is exactly what this change exists to avoid; JobRunr retries.
                LOG.warn("Review analysis for {} failed transiently before a run was acquired;"
                        + " leaving it for JobRunr to retry: {}", reviewId, failureReason(failure));
            }
            return;
        }
        if (run == null && !failReview) {
            return;
        }
        transactionTemplate.executeWithoutResult(status -> {
            if (run != null) {
                analysisRepository.markRunFailed(organizationId, run.id(),
                        failureReason(failure), clock.instant());
            }
            if (failReview) {
                reviewRepository.markFailed(organizationId, reviewId,
                        failureReason(failure), clock.instant());
            }
        });
    }

    private void recordTransientFailure(UUID organizationId, UUID reviewId,
            ReviewAnalysisRun run, Attempt attempt, RuntimeException failure, boolean failReview) {
        var reason = transientReason(failure);
        var detail = failureReason(failure);
        try {
            // A new transaction on purpose: the failed check write has rolled back, and the counter
            // must survive it whether or not this analyze call runs inside someone else's work.
            failureTransactionTemplate.executeWithoutResult(status -> {
                var count = analysisRepository.recordTransientFailure(organizationId, run.id(),
                        reason, attempt.executionId(), attempt.leaseId());
                if (count.isEmpty()) {
                    LOG.warn("Review analysis run {} is no longer RUNNING; transient failure not"
                            + " counted: {}", run.id(), detail);
                    return;
                }
                if (count.getAsInt() >= maxTransientFailures) {
                    LOG.warn("Review analysis run {} reached {} transient failures; failing it"
                            + " terminally: {}", run.id(), count.getAsInt(), detail);
                    if (analysisRepository.markRunFailed(organizationId, run.id(), detail,
                            clock.instant()) && failReview) {
                        reviewRepository.markFailed(organizationId, reviewId, detail,
                                clock.instant());
                    }
                } else {
                    LOG.warn("Review analysis run {} survived transient failure {}/{}; JobRunr"
                            + " retries the same run: {}", run.id(), count.getAsInt(),
                            maxTransientFailures, detail);
                }
            });
        } catch (RuntimeException counterFailure) {
            LOG.warn("Unable to record the transient failure of review analysis run {}; the"
                    + " database may be down, so JobRunr retries: {}", run.id(), detail,
                    counterFailure);
        }
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
        var attempt = new Attempt();
        var attemptStartedAt = clock.instant();
        try {
            var snapshot = reviewRepository.findSnapshot(organizationId, reviewId).orElseThrow(
                    () -> new IllegalStateException("Review snapshot missing: " + reviewId));
            acquiredRun = resolveRecomputeRun(organizationId, reviewId);
            var run = acquiredRun;
            var semantics = contractEnrichmentService.getSemanticsForOrganization(
                    organizationId, snapshot.contractVersionId());
            var environment = evaluateEnvironment(snapshot, semantics);
            executePlannedChecks(review, snapshot, run, semantics, environment, attempt,
                    attemptStartedAt);
            completeIfDone(organizationId, reviewId, run);
        } catch (RuntimeException e) {
            handleFailure(organizationId, reviewId, acquiredRun, attempt, e, false);
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

    /**
     * Whether the frozen snapshot still matches every live input. Exposed as a boolean so callers
     * outside the review module do not depend on the check-package environment type.
     */
    public boolean environmentFullyMatches(ReviewSnapshot snapshot, ContractSemantics semantics) {
        return evaluateEnvironment(snapshot, semantics).fullyMatches();
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
            ContractSemantics semantics, AnalysisEnvironment environment, Attempt attempt,
            Instant attemptStartedAt) {
        var checks = reviewRepository.findPlanChecks(review.organizationId(), review.id());
        var deadline = analysisDeadline(run.startedAt(), attemptStartedAt, analysisBudget,
                maxTransientFailures);
        if (!clock.instant().isBefore(deadline)) {
            degradeRemaining(review, run, checks, 0, attempt);
            return;
        }
        var embeddings = new ClauseEmbeddingCache(embeddingService);
        MDC.put("reviewId", review.id().toString());
        MDC.put("runId", run.id().toString());
        try {
            // A resumed run already has terminal checks; do not re-embed clause text those checks
            // will never read again.
            var terminalCheckIds = analysisRepository.findExecutions(
                            review.organizationId(), run.id()).stream()
                    .filter(execution -> execution.status().terminal())
                    .map(ReviewCheckExecution::planCheckId)
                    .collect(java.util.stream.Collectors.toSet());
            var checksNeedingWork = checks.stream()
                    .filter(check -> !terminalCheckIds.contains(check.id()))
                    .toList();
            prefetchClauseEmbeddings(checksNeedingWork, semantics, embeddings);
        } finally {
            MDC.remove("reviewId");
            MDC.remove("runId");
        }
        var evidenceCache = new ReviewEvidenceCache(evidenceService, embeddings);
        var structure = structure(snapshot);
        for (int index = 0; index < checks.size(); index++) {
            var check = checks.get(index);
            var existing = analysisRepository.findExecution(
                    review.organizationId(), run.id(), check.id());
            if (existing.isPresent() && existing.get().status().terminal()) {
                continue;
            }
            if (!clock.instant().isBefore(deadline)) {
                degradeRemaining(review, run, checks, index, attempt);
                return;
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
            attempt.heldBy(execution.id(), lease.get());
            MDC.put("reviewId", review.id().toString());
            MDC.put("runId", run.id().toString());
            MDC.put("checkId", check.id().toString());
            try {
                var context = new ReviewCheckContext(review, snapshot, check, run.id(),
                        semantics, environment, evidenceCache, structure.headingTexts(),
                        structure.runInHeadings(), structure.untrackableHeadings(), clock.instant());
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
                        analysisRepository.replaceEvidenceItems(review.organizationId(),
                                review.id(), run.id(), check.id(), outcome.evidenceItems());
                    });
                    attempt.clear();
                } catch (LeaseLostException e) {
                    // another worker reclaimed this execution; it owns the terminal write
                    attempt.clear();
                    continue;
                }
            } finally {
                MDC.remove("reviewId");
                MDC.remove("runId");
                MDC.remove("checkId");
            }
        }
    }

    /**
     * Embeds every clause text the precedent lane and position search can ask for in one batch,
     * so both lanes read the same run-scoped vectors instead of one request per clause.
     */
    private void prefetchClauseEmbeddings(
            List<ReviewPlanCheck> checks, ContractSemantics semantics,
            ClauseEmbeddingCache embeddings) {
        if (!embeddingService.isConfigured()) {
            return;
        }
        var subjectIds = checks.stream()
                .filter(check -> check.mechanism() == CheckMechanism.POLICY_COMPARISON)
                .map(ReviewPlanCheck::subjectHeadingNodeId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(java.util.stream.Collectors.toSet());
        var texts = new ArrayList<String>();
        for (var clause : semantics.clauses()) {
            if (subjectIds.contains(clause.headingNodeId())
                    && clause.contentText() != null && !clause.contentText().isBlank()) {
                texts.add(clause.contentText());
            }
        }
        embeddings.prefetch(texts);
    }

    /**
     * The review's time budget is spent: every check not yet terminal becomes DEGRADED for the
     * same code-owned reason, so the run still reaches {@link #completeIfDone} and the existing
     * all-degraded finalisation rule applies.
     */
    private void degradeRemaining(Review review, ReviewAnalysisRun run,
            List<ReviewPlanCheck> checks, int fromIndex, Attempt attempt) {
        var outcome = CheckOutcome.degraded(DEADLINE_DEGRADED_REASON);
        for (int index = fromIndex; index < checks.size(); index++) {
            var check = checks.get(index);
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
            attempt.heldBy(execution.id(), lease.get());
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    if (!analysisRepository.completeExecution(review.organizationId(),
                            execution.id(), lease.get(), outcome.status(), null,
                            outcome.detail(), 0, clock.instant())) {
                        throw new LeaseLostException(execution.id());
                    }
                });
                attempt.clear();
            } catch (LeaseLostException e) {
                // another worker reclaimed this execution; it owns the terminal write
                attempt.clear();
            }
        }
    }

    private record StructureFacts(
            Map<UUID, String> headingTexts, Map<UUID, String> runInHeadings,
            java.util.Set<UUID> untrackableHeadings) {
    }

    /**
     * Structure facts loaded once per run: node text by node id (the comparison state names the
     * subject clause heading), the heading part of run-in headings, and the clauses a tracked
     * change cannot replace. A structure read failure leaves them empty rather than failing the
     * run; the check still judges the clause text.
     */
    private StructureFacts structure(ReviewSnapshot snapshot) {
        try {
            var nodes = contractQueryService.getStructureForOrganization(
                            snapshot.organizationId(), snapshot.contractId(),
                            snapshot.contractVersionNumber())
                    .nodes();
            var texts = new java.util.HashMap<UUID, String>();
            var runIn = new java.util.HashMap<UUID, String>();
            for (var node : nodes) {
                if (node.text() != null && !node.text().isBlank()) {
                    texts.putIfAbsent(node.id(), node.text());
                }
                if (node.runInHeading() != null) {
                    runIn.put(node.id(), node.runInHeading());
                }
            }
            return new StructureFacts(texts, runIn,
                    new com.rivell.workbench.contracts.ContractRegionSegmenter()
                            .untrackableHeadings(nodes));
        } catch (RuntimeException e) {
            return new StructureFacts(Map.of(), Map.of(), java.util.Set.of());
        }
    }

    private void completeIfDone(UUID organizationId, UUID reviewId, ReviewAnalysisRun run) {
        var checks = reviewRepository.findPlanChecks(organizationId, reviewId);
        var executions = analysisRepository.findExecutions(organizationId, run.id());
        if (executions.size() == checks.size()
                && executions.stream().allMatch(execution -> execution.status().terminal())) {
            var now = clock.instant();
            var signals = analysisRepository.findSignals(organizationId, run.id());
            // assemble before the completion transaction: a non-transient assembly failure fails
            // this generation terminally; a transient one is counted and retried by analyze
            var assembled = findingAssembler.assemble(run, signals, now);
            var coverage = coverage(organizationId, reviewId, run, checks, executions, signals,
                    assembled);
            transactionTemplate.executeWithoutResult(status -> {
                if (analysisRepository.markRunCompleted(organizationId, run.id(), now)) {
                    if (!analysisRepository.savePlaybookCoverage(organizationId, run.id(), coverage)) {
                        throw new IllegalStateException(
                                "Run disappeared before its coverage was stored: " + run.id());
                    }
                    findingRepository.replaceFindings(organizationId, reviewId, run.id(),
                            assembled);
                    reviewRepository.markCompleted(organizationId, reviewId, now);
                }
            });
        }
    }

    /**
     * Coverage is a display artifact, so a failure to read the pinned positions must not fail the
     * review: the run completes with no coverage section rather than claiming one it could not
     * compute. The warning is the operator's record of why it is absent, with the cause.
     */
    private PlaybookCoverage coverage(UUID organizationId, UUID reviewId, ReviewAnalysisRun run,
            List<ReviewPlanCheck> checks, List<ReviewCheckExecution> executions,
            List<RawReviewSignal> signals, List<Finding> findings) {
        try {
            var snapshot = reviewRepository.findSnapshot(organizationId, reviewId).orElseThrow(
                    () -> new IllegalStateException("Review snapshot missing: " + reviewId));
            return playbookCoverageBuilder.build(snapshot, checks, executions, signals, findings);
        } catch (RuntimeException e) {
            LOG.warn("Could not compute playbook coverage for run {}", run.id(), e);
            return null;
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
                    : CheckOutcome.degraded(ProviderFailures.degradedReason(e));
        } catch (RuntimeException e) {
            if (isTransientFailure(e)) {
                // Infrastructure, not a legal verdict: let analyze count it and JobRunr retry the
                // same run. Writing FAILED here would silently lose the check's work.
                throw e;
            }
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

    /**
     * The lease this analyze call currently holds. One attempt runs one check at a time, so a
     * failure handler can release exactly that lease without waiting for its 10-minute expiry.
     * Cleared only after the check's terminal transaction commits (or loses the lease).
     */
    private static final class Attempt {
        private UUID executionId;
        private UUID leaseId;

        void heldBy(UUID executionId, UUID leaseId) {
            this.executionId = executionId;
            this.leaseId = leaseId;
        }

        void clear() {
            this.executionId = null;
            this.leaseId = null;
        }

        UUID executionId() {
            return executionId;
        }

        UUID leaseId() {
            return leaseId;
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

    /**
     * A failure is transient when it, or anything it was caused by, is an infrastructure failure
     * that a retry can plausibly survive. Everything else keeps the v1 rule: terminal FAILED.
     */
    static boolean isTransientFailure(Throwable failure) {
        var visited = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<Throwable, Boolean>());
        Throwable current = failure;
        while (current != null && visited.add(current)) {
            if (isTransientType(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * The code-owned reason recorded for a transient failure: the transient type that made the
     * chain retryable, never the driver or provider message. A SQL message can quote statement or
     * key detail, and diagnostics exposes this value to every reviewer who can read the review.
     */
    static String transientReason(Throwable failure) {
        var visited = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<Throwable, Boolean>());
        Throwable current = failure;
        while (current != null && visited.add(current)) {
            if (isTransientType(current)) {
                return current.getClass().getSimpleName();
            }
            current = current.getCause();
        }
        return failure.getClass().getSimpleName();
    }

    private static boolean isTransientType(Throwable failure) {
        return failure instanceof TransientDataAccessException
                || failure instanceof RecoverableDataAccessException
                || failure instanceof DataAccessResourceFailureException
                || failure instanceof QueryTimeoutException
                || failure instanceof SQLTransientException
                || failure instanceof LeaseLostException;
    }

    /**
     * The attempt's own budget, capped so one run cannot keep restarting it forever: at most one
     * budget per attempt, and at most {@code maxTransientFailures + 1} budgets for the run, capped
     * at 1000 so an absurd retry setting cannot stretch the run window without bound.
     */
    static Instant analysisDeadline(Instant runStartedAt, Instant attemptStartedAt,
            Duration budget, int maxTransientFailures) {
        var attemptLimit = attemptStartedAt.plus(budget);
        var attempts = Math.min(maxTransientFailures + 1L, 1000L);
        var runLimit = runStartedAt.plus(budget.multipliedBy(attempts));
        return attemptLimit.isBefore(runLimit) ? attemptLimit : runLimit;
    }

    private static int count(
            java.util.List<ReviewCheckExecution> executions, CheckExecutionStatus status) {
        return (int) executions.stream().filter(e -> e.status() == status).count();
    }
}
