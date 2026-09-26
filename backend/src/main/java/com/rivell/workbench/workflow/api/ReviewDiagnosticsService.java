package com.rivell.workbench.workflow.api;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
 * question without a database session, and without exposing contract text or model output.
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

        var findings = run.flatMap(r -> analysisRepository.findLatestCompletedRun(
                organizationId, reviewId).map(completed ->
                        findingRepository.findFindings(organizationId, completed.id())))
                .orElse(List.of());
        var decisions = decisionsView(organizationId, reviewId);

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
                backgroundWorkerEnabled, analysis, countsView(findings), decisions, finalization,
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
        var checks = new ArrayList<ReviewDiagnosticsView.CheckView>();
        for (var execution : analysisRepository.findExecutions(organizationId, run.id())) {
            var degraded = execution.detail() == null ? null
                    : (String) execution.detail().get("degradedReason");
            checks.add(new ReviewDiagnosticsView.CheckView(
                    checkKey(organizationId, run.reviewId(), execution.planCheckId()),
                    execution.status().name(), execution.signalCount(), degraded,
                    execution.startedAt(), execution.completedAt(),
                    execution.startedAt() == null ? null
                            : Duration.between(execution.startedAt(),
                                    execution.completedAt() == null ? now
                                            : execution.completedAt()).toMillis()));
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

    private String checkKey(UUID organizationId, UUID reviewId, UUID planCheckId) {
        return reviewRepository.findPlanChecks(organizationId, reviewId).stream()
                .filter(check -> check.id().equals(planCheckId))
                .map(check -> check.checkKey())
                .findFirst()
                .orElse(planCheckId.toString());
    }

    private ReviewDiagnosticsView.CountsView countsView(List<Finding> findings) {
        return new ReviewDiagnosticsView.CountsView(findings.size(),
                (int) count(findings, FindingClassification.BLOCKING),
                (int) count(findings, FindingClassification.REVIEW_REQUIRED),
                (int) count(findings, FindingClassification.INFORMATIONAL));
    }

    private long count(List<Finding> findings, FindingClassification classification) {
        return findings.stream()
                .filter(finding -> finding.reviewClassification() == classification)
                .count();
    }

    private ReviewDiagnosticsView.DecisionsView decisionsView(UUID organizationId, UUID reviewId) {
        var decisions = decisionRepository.findCurrentForRun(organizationId, reviewId, null, 1000);
        return new ReviewDiagnosticsView.DecisionsView(
                (int) decisions.stream().filter(d -> d.action() == DecisionAction.ACCEPT).count(),
                (int) decisions.stream().filter(d -> d.action() == DecisionAction.EDIT).count(),
                (int) decisions.stream().filter(d -> d.action() == DecisionAction.REJECT).count(),
                (int) decisions.stream().filter(d -> d.action() == DecisionAction.ESCALATE).count());
    }

    private Instant completion(Instant startedAt, Instant completedAt) {
        return completedAt == null ? startedAt : completedAt;
    }

    private Long age(Instant completedAt) {
        return completedAt == null ? null
                : Duration.between(completedAt, clock.instant()).toSeconds();
    }
}
