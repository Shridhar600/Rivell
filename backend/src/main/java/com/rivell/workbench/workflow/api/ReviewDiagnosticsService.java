package com.rivell.workbench.workflow.api;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rivell.workbench.contracts.ContractEnrichmentService;
import com.rivell.workbench.contracts.SemanticsDigest;
import com.rivell.workbench.organization.AuthorizationService;
import com.rivell.workbench.organization.IdentityProvider;
import com.rivell.workbench.organization.Permissions;
import com.rivell.workbench.organization.UserIdentity;
import com.rivell.workbench.redline.ApprovedChangeSetRepository;
import com.rivell.workbench.redline.RedlineExecutionRepository;
import com.rivell.workbench.review.Finding;
import com.rivell.workbench.review.FindingClassification;
import com.rivell.workbench.review.FindingRepository;
import com.rivell.workbench.review.Review;
import com.rivell.workbench.review.ReviewAnalysisRepository;
import com.rivell.workbench.review.ReviewAnalysisRun;
import com.rivell.workbench.review.ReviewNotFoundException;
import com.rivell.workbench.review.ReviewRepository;
import com.rivell.workbench.review.ReviewSnapshot;
import com.rivell.workbench.workflow.DecisionAction;
import com.rivell.workbench.workflow.HumanDecisionRepository;
import com.rivell.workbench.workflow.ReviewFinalizationService;
import com.rivell.workbench.workflow.UnauthorizedWorkflowAccessException;

/**
 * Assembles developer diagnostics for one review.
 *
 * <p>M10. The product's hardest failure to diagnose is a review that simply stops: with the default
 * configuration there is no background worker, so an enqueued analysis is never picked up and the
 * review sits in {@code ANALYSIS_QUEUED} with nothing to indicate why. This service answers that
 * question without a database session, and without exposing contract text. Model output appears
 * only as categories and probabilities (the policy comparison's judged positions, for the eval).
 *
 * <p>Every read is organization-scoped, and the joins between modules are on identifiers that the
 * schema already ties to the owning organization, so no hop can cross a tenant.
 */
@Service
@Transactional(readOnly = true)
public class ReviewDiagnosticsService {

    /**
     * How long a run may stay in flight before it is called out. Generous on purpose: the aim is to
     * surface a wedged run, not to second-guess a slow one.
     */
    private static final Duration STUCK_RUN_THRESHOLD = Duration.ofMinutes(15);

    private final IdentityProvider identityProvider;
    private final AuthorizationService authorizationService;
    private final ReviewRepository reviewRepository;
    private final ReviewAnalysisRepository analysisRepository;
    private final FindingRepository findingRepository;
    private final HumanDecisionRepository decisionRepository;
    private final ApprovedChangeSetRepository changeSetRepository;
    private final RedlineExecutionRepository redlineExecutionRepository;
    private final ContractEnrichmentService contractEnrichmentService;
    private final Clock clock;
    private final boolean backgroundWorkerEnabled;

    public ReviewDiagnosticsService(
            IdentityProvider identityProvider,
            AuthorizationService authorizationService,
            ReviewRepository reviewRepository,
            ReviewAnalysisRepository analysisRepository,
            FindingRepository findingRepository,
            HumanDecisionRepository decisionRepository,
            ApprovedChangeSetRepository changeSetRepository,
            RedlineExecutionRepository redlineExecutionRepository,
            ContractEnrichmentService contractEnrichmentService,
            Clock clock,
            @Value("${jobrunr.background-job-server.enabled:false}")
            boolean backgroundWorkerEnabled) {
        this.identityProvider = identityProvider;
        this.authorizationService = authorizationService;
        this.reviewRepository = reviewRepository;
        this.analysisRepository = analysisRepository;
        this.findingRepository = findingRepository;
        this.decisionRepository = decisionRepository;
        this.changeSetRepository = changeSetRepository;
        this.redlineExecutionRepository = redlineExecutionRepository;
        this.contractEnrichmentService = contractEnrichmentService;
        this.clock = clock;
        this.backgroundWorkerEnabled = backgroundWorkerEnabled;
    }

    public ReviewDiagnosticsView forReview(UUID reviewId) {
        var identity = identityProvider.currentIdentity();
        if (!authorizationService.isAuthorized(identity, Permissions.REVIEW_READ)) {
            throw new UnauthorizedWorkflowAccessException(identity.userId());
        }
        var organizationId = identity.organizationId();
        var review = reviewRepository.find(organizationId, reviewId)
                .orElseThrow(() -> new ReviewNotFoundException(reviewId));

        var stalled = new ArrayList<String>();
        var run = analysisRepository.findLatestRun(organizationId, reviewId);
        var analysis = run.map(r -> analysisView(organizationId, r, stalled)).orElse(null);
        if (run.isEmpty()) {
            stalled.add("No analysis run has been created for this review.");
        }

        // One lookup, reused by the finding counts and the decision counters. Both previously asked
        // the repository for the latest completed run separately, which is a duplicate read of the
        // same row on the request an operator makes when something is already wrong.
        var completedRun = analysisRepository.findLatestCompletedRun(organizationId, reviewId);
        var decisions = decisionsView(organizationId, reviewId, completedRun);

        var changeSet = changeSetRepository.findByReview(organizationId, reviewId);
        var finalization = changeSet
                .map(set -> new ReviewDiagnosticsView.FinalizationView(true, set.id(),
                        set.finalizedAt(), set.changes().size()))
                .orElseGet(() -> new ReviewDiagnosticsView.FinalizationView(false, null, null, 0));

        var redline = redlineExecutionRepository.findByReview(organizationId, reviewId)
                .map(execution -> new ReviewDiagnosticsView.RedlineView(
                        execution.status().name(),
                        execution.outcome() == null ? null : execution.outcome().name(),
                        execution.failureCode() == null ? null : execution.failureCode().name(),
                        execution.changeCount(),
                        execution.artifact() != null,
                        age(completion(execution.startedAt(), execution.completedAt()))))
                .orElse(null);

        return new ReviewDiagnosticsView(review.id(), review.contractId(),
                review.contractVersionNumber(), review.status().name(), review.failureReason(),
                backgroundWorkerEnabled, analysis, countsView(organizationId, completedRun),
                decisions, finalization,
                redline, stalled);
    }

    private ReviewDiagnosticsView.AnalysisRunView analysisView(
            UUID organizationId, ReviewAnalysisRun run, List<String> stalled) {
        var now = clock.instant();
        var age = run.startedAt() == null ? null
                : Duration.between(run.startedAt(), completion(run.startedAt(),
                        run.completedAt())).toSeconds();
        if (run.completedAt() == null && run.startedAt() != null
                && Duration.between(run.startedAt(), now).compareTo(STUCK_RUN_THRESHOLD) > 0) {
            stalled.add("The analysis run has been in flight for " + age
                    + "s with no completion. It may be wedged.");
        }
        // Loaded once per request, not once per check: resolving each key separately re-read the
        // whole plan for every execution.
        var planChecks = planChecks(organizationId, run.reviewId());
        var checks = new ArrayList<ReviewDiagnosticsView.CheckView>();
        for (var execution : analysisRepository.findExecutions(organizationId, run.id())) {
            var degraded = execution.detail() == null ? null
                    : (String) execution.detail().get("degradedReason");
            var plan = planChecks.get(execution.planCheckId());
            checks.add(new ReviewDiagnosticsView.CheckView(
                    plan == null ? execution.planCheckId().toString() : plan.checkKey(),
                    plan == null ? null : plan.subjectHeadingNodeId(),
                    execution.status().name(), execution.signalCount(), degraded,
                    failureType(execution.detail()),
                    execution.startedAt(), execution.completedAt(),
                    execution.startedAt() == null ? null
                            : Duration.between(execution.startedAt(),
                                    execution.completedAt() == null ? now
                                            : execution.completedAt()).toMillis(),
                    judgedPositions(execution.detail()),
                    text(execution.detail(), "probe"),
                    number(execution.detail() == null ? null
                            : execution.detail().get("uncoveredProbability")),
                    text(execution.detail(), "route"),
                    bool(execution.detail(), "addressed"),
                    searchFound(execution.detail()),
                    searchFailed(execution.detail()),
                    text(execution.detail(), "spotted"),
                    integer(execution.detail(), "omissionCandidates"),
                    integer(execution.detail(), "searchedClauses"),
                    presenceProbabilities(execution.detail()),
                    count(execution.detail(), "addressedBySearch"),
                    count(execution.detail(), "flaggedBySearch"),
                    strings(execution.detail(), "definitionTerms")));
        }
        return new ReviewDiagnosticsView.AnalysisRunView(run.id(), run.runNumber(),
                run.status().name(), run.trigger().name(), run.pipelineVersion(),
                run.startedAt(), run.completedAt(), age, run.failureReason(),
                environmentMatches(organizationId, run.reviewId()), checks);
    }

    /**
     * Whether the frozen snapshot still describes the live world. This is the same comparison the
     * analysis itself makes, reported rather than acted on, so an operator can see drift before it
     * turns into a degraded run.
     */
    private Boolean environmentMatches(UUID organizationId, UUID reviewId) {
        var snapshot = reviewRepository.findSnapshot(organizationId, reviewId);
        if (snapshot.isEmpty()) {
            return null;
        }
        return environmentMatches(snapshot.get());
    }

    private Boolean environmentMatches(ReviewSnapshot snapshot) {
        try {
            var semantics = contractEnrichmentService.getSemanticsForOrganization(
                    snapshot.organizationId(), snapshot.contractId(),
                    snapshot.contractVersionNumber());
            return semantics != null
                    && SemanticsDigest.of(semantics).equals(snapshot.semanticsDigest());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Map<UUID, com.rivell.workbench.review.ReviewPlanCheck> planChecks(
            UUID organizationId, UUID reviewId) {
        var checks = new java.util.HashMap<UUID, com.rivell.workbench.review.ReviewPlanCheck>();
        reviewRepository.findPlanChecks(organizationId, reviewId)
                .forEach(check -> checks.put(check.id(), check));
        return checks;
    }

    /**
     * A policy comparison's judged positions as the check stored them: keys, categories,
     * probabilities and, on engine v2, the route and the second opinion's outcome and category.
     * Clause and position text, and the second opinion's words, never reach the check detail.
     */
    static List<ReviewDiagnosticsView.JudgedPositionView> judgedPositions(Map<String, Object> detail) {
        if (detail == null || !(detail.get("positions") instanceof List<?> positions)) {
            return null;
        }
        var views = new ArrayList<ReviewDiagnosticsView.JudgedPositionView>();
        for (var item : positions) {
            if (item instanceof Map<?, ?> entry) {
                views.add(new ReviewDiagnosticsView.JudgedPositionView(
                        text(entry.get("positionKey")), text(entry.get("positionCandidateId")),
                        text(entry.get("category")), number(entry.get("flagProbability")),
                        entry.get("flagged") instanceof Boolean flagged ? flagged : null,
                        probabilities(entry.get("probabilities")),
                        text(entry.get("probeCategory")),
                        entry.get("probeFlagged") instanceof Boolean probeFlagged ? probeFlagged : null,
                        probabilities(entry.get("probeProbabilities")),
                        text(entry.get("route")),
                        text(entry.get("secondOpinionOutcome")),
                        text(entry.get("secondOpinionCategory")),
                        text(entry.get("foundBy"))));
            }
        }
        return views;
    }

    private static String text(Map<String, Object> detail, String key) {
        return detail == null ? null : text(detail.get(key));
    }

    private static String text(Object value) {
        return value instanceof String string && !string.isEmpty() ? string : null;
    }

    private static Double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static Boolean bool(Map<String, Object> detail, String key) {
        return detail != null && detail.get(key) instanceof Boolean flag ? flag : null;
    }

    private static Integer integer(Map<String, Object> detail, String key) {
        return detail == null ? null : integer(detail.get(key));
    }

    private static Integer integer(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    /** Count of one detailed list, or null when the detail does not carry it. */
    private static Integer count(Map<String, Object> detail, String key) {
        return detail != null && detail.get(key) instanceof List<?> entries
                ? entries.size() : null;
    }

    /** The detail's string list under one key; null when the detail does not carry it. */
    private static List<String> strings(Map<String, Object> detail, String key) {
        if (detail == null || !(detail.get(key) instanceof List<?> entries)) {
            return null;
        }
        var result = new ArrayList<String>();
        for (var entry : entries) {
            if (entry instanceof String text && !text.isEmpty()) {
                result.add(text);
            }
        }
        return result;
    }

    private static Integer searchFound(Map<String, Object> detail) {
        var search = detail == null ? null : detail.get("search");
        return search instanceof Map<?, ?> map && map.get("found") instanceof Number found
                ? found.intValue() : null;
    }

    private static Boolean searchFailed(Map<String, Object> detail) {
        var search = detail == null ? null : detail.get("search");
        return search instanceof Map<?, ?> map ? "FAILED".equals(map.get("outcome")) : null;
    }

    /** The omission detail's presence entries as candidateId -> probability; null when absent. */
    private static Map<String, Double> presenceProbabilities(Map<String, Object> detail) {
        if (detail == null || !(detail.get("presence") instanceof List<?> entries)) {
            return null;
        }
        var result = new java.util.LinkedHashMap<String, Double>();
        for (var entry : entries) {
            if (entry instanceof Map<?, ?> map
                    && map.get("candidateId") instanceof String id
                    && map.get("probability") instanceof Number probability) {
                result.put(id, probability.doubleValue());
            }
        }
        return result;
    }

    private static Map<String, Double> probabilities(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        var result = new java.util.LinkedHashMap<String, Double>();
        map.forEach((option, probability) -> {
            if (option instanceof String name && probability instanceof Number number) {
                result.put(name, number.doubleValue());
            }
        });
        return result;
    }

    /** The exception type of a failed check, never its message (which may quote data). */
    private static String failureType(Map<String, Object> detail) {
        if (detail == null || !(detail.get("failureReason") instanceof String reason)) {
            return null;
        }
        var colon = reason.indexOf(':');
        return colon < 0 ? reason : reason.substring(0, colon);
    }

    /**
     * Four integers, computed in the database. The counts came from materialising every finding of
     * the run together with its concept and claim children, and then discarding the lot; on a large
     * or degraded run that is the most expensive thing a diagnostic read can do, and it is the run
     * an operator opens this screen for.
     */
    private ReviewDiagnosticsView.CountsView countsView(
            UUID organizationId, Optional<ReviewAnalysisRun> completedRun) {
        var counts = completedRun
                .map(run -> findingRepository.countFindingsByClassification(organizationId, run.id()))
                .orElse(Map.of());
        return new ReviewDiagnosticsView.CountsView(
                (int) counts.values().stream().mapToLong(Long::longValue).sum(),
                counts.getOrDefault(FindingClassification.BLOCKING, 0L).intValue(),
                counts.getOrDefault(FindingClassification.REVIEW_REQUIRED, 0L).intValue(),
                counts.getOrDefault(FindingClassification.INFORMATIONAL, 0L).intValue());
    }

    private ReviewDiagnosticsView.DecisionsView decisionsView(
            UUID organizationId, UUID reviewId, Optional<ReviewAnalysisRun> completedRun) {
        // The run id is required: the repository filters on run_id, and `run_id = NULL` is never true
        // in SQL, so passing null returned an empty list and reported every review as having no human
        // decisions at all. These counters are the eval-label surface, so a silent zero is worse than
        // an absent field.
        var runId = completedRun.map(ReviewAnalysisRun::id).orElse(null);
        if (runId == null) {
            return new ReviewDiagnosticsView.DecisionsView(0, 0, 0, 0);
        }
        // Aggregated rather than counted from a bounded list. The list form read limit + 1 rows and
        // then counted them, so a run with more decisions than the limit reported the limit, not
        // the truth, on a surface whose whole purpose is to be trusted when something is wrong.
        var counts = decisionRepository.countCurrentByAction(organizationId, reviewId, runId);
        return new ReviewDiagnosticsView.DecisionsView(
                counts.getOrDefault(DecisionAction.ACCEPT, 0L).intValue(),
                counts.getOrDefault(DecisionAction.EDIT, 0L).intValue(),
                counts.getOrDefault(DecisionAction.REJECT, 0L).intValue(),
                counts.getOrDefault(DecisionAction.ESCALATE, 0L).intValue());
    }

    private Instant completion(Instant startedAt, Instant completedAt) {
        return completedAt == null ? startedAt : completedAt;
    }

    private Long age(Instant completedAt) {
        return completedAt == null ? null
                : Duration.between(completedAt, clock.instant()).toSeconds();
    }
}
