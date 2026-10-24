package com.rivell.workbench.workflow;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.rivell.workbench.contracts.ContractQueryService;
import com.rivell.workbench.redline.ApprovedChangeSet;
import com.rivell.workbench.redline.ApprovedChangeSetRepository;
import com.rivell.workbench.review.Finding;
import com.rivell.workbench.review.FindingRepository;
import com.rivell.workbench.review.Review;
import com.rivell.workbench.review.CheckExecutionStatus;
import com.rivell.workbench.review.ReviewAnalysisRepository;
import com.rivell.workbench.review.ReviewAnalysisService;
import com.rivell.workbench.review.ReviewCheckExecution;
import com.rivell.workbench.review.ReviewNotFoundException;
import com.rivell.workbench.review.ReviewRepository;
import com.rivell.workbench.review.ReviewStatus;

/**
 * Human-resolution read model: current decisions, derived status/counts, readiness
 * blockers, and the executable changes a finalization would freeze. Read-only; the
 * caller has already resolved identity and organization.
 */
public class HumanReviewService {

    private final ReviewRepository reviewRepository;
    private final ReviewAnalysisRepository analysisRepository;
    private final FindingRepository findingRepository;
    private final HumanDecisionRepository decisionRepository;
    private final ApprovedChangeSetRepository changeSetRepository;
    private final ContractQueryService contractQueryService;

    public HumanReviewService(
            ReviewRepository reviewRepository,
            ReviewAnalysisRepository analysisRepository,
            FindingRepository findingRepository,
            HumanDecisionRepository decisionRepository,
            ApprovedChangeSetRepository changeSetRepository,
            ContractQueryService contractQueryService) {
        this.reviewRepository = reviewRepository;
        this.analysisRepository = analysisRepository;
        this.findingRepository = findingRepository;
        this.decisionRepository = decisionRepository;
        this.changeSetRepository = changeSetRepository;
        this.contractQueryService = contractQueryService;
    }

    public HumanReviewState state(UUID organizationId, UUID reviewId) {
        var review = reviewRepository.find(organizationId, reviewId)
                .orElseThrow(() -> new ReviewNotFoundException(reviewId));
        var changeSet = changeSetRepository.findByReview(organizationId, reviewId);
        var activeRun = analysisRepository.findLatestCompletedRun(organizationId, reviewId);
        var findings = activeRun
                .map(run -> findingRepository.findFindings(organizationId, run.id()))
                .orElse(List.of());
        Map<UUID, HumanDecision> decisions = activeRun
                .map(run -> decisionRepository
                        .findCurrentForRun(organizationId, reviewId, run.id()).stream()
                        .collect(Collectors.toMap(HumanDecision::findingId,
                                decision -> decision, (first, second) -> first)))
                .orElse(Map.of());
        var headings = contractQueryService.headingNodeIdsForOrganization(
                organizationId, review.contractId(), review.contractVersionNumber());
        // DEGRADED is overloaded: it covers a check that could not trust its inputs (the analysis
        // environment no longer matches the review snapshot, so every check degrades and the run
        // reports COMPLETED with nothing to show), a check that ran fine and honestly reported
        // partial coverage, and a check the analysis budget left unrun. The first and last are
        // finalization hazards; an honest partial result still covers its clause and must stay
        // finalizable.
        var checkOutcomes = activeRun
                .map(run -> analysisRepository.findExecutions(organizationId, run.id()))
                .orElse(List.of());
        var plannedChecks = reviewRepository.findPlanChecks(organizationId, reviewId).size();
        var completedChecks = (int) checkOutcomes.stream()
                .filter(execution -> execution.status() == CheckExecutionStatus.COMPLETED)
                .count();
        // M10 review finding: the false-clean signature only exists once a run has actually
        // completed. Before that there are no executions at all, so "none completed" was true for
        // every freshly created review and the review was told its analysis environment was broken
        // when nothing had run yet. Only a completed run can have produced no trustworthy result.
        var analysisComplete = review.status() == ReviewStatus.COMPLETED && activeRun.isPresent();
        var producedNoTrustworthyResult = activeRun.isPresent()
                && plannedChecks > 0
                && completedChecks == 0;
        // A completed run can still have checks that failed, were skipped, or ran out of the
        // analysis budget. Those clauses were never really reviewed, so the run is not finalizable
        // even when the check counts look mostly healthy. Before completion the count is not a
        // finalization fact, and the list read must agree, so it stays zero.
        var checksDidNotRun = analysisComplete
                ? (int) checkOutcomes.stream().filter(HumanReviewService::didNotRun).count()
                : 0;
        var assessment = HumanReviewResolver.resolve(findings, decisions, analysisComplete,
                producedNoTrustworthyResult, checksDidNotRun, changeSet.isPresent(), headings);
        return new HumanReviewState(review, activeRun.map(run -> run.id()).orElse(null),
                findings, decisions, changeSet, assessment);
    }

    private static boolean didNotRun(ReviewCheckExecution execution) {
        return execution.status() == CheckExecutionStatus.FAILED
                || execution.status() == CheckExecutionStatus.SKIPPED
                || (execution.status() == CheckExecutionStatus.DEGRADED
                        && ReviewAnalysisService.DEADLINE_DEGRADED_REASON
                                .equals(execution.detail().get("degradedReason")));
    }

    public List<HumanDecision> currentDecisions(
            UUID organizationId, UUID reviewId, int limit) {
        var review = reviewRepository.find(organizationId, reviewId)
                .orElseThrow(() -> new ReviewNotFoundException(reviewId));
        return analysisRepository.findLatestCompletedRun(organizationId, review.id())
                .map(run -> decisionRepository.findCurrentForRun(
                        organizationId, reviewId, run.id(), limit))
                .orElse(List.of());
    }

    public Optional<ApprovedChangeSet> changeSet(UUID organizationId, UUID reviewId) {
        reviewRepository.find(organizationId, reviewId)
                .orElseThrow(() -> new ReviewNotFoundException(reviewId));
        return changeSetRepository.findByReview(organizationId, reviewId);
    }

    public List<HumanDecision> history(
            UUID organizationId, UUID reviewId, UUID findingId, int limit) {
        var review = reviewRepository.find(organizationId, reviewId)
                .orElseThrow(() -> new ReviewNotFoundException(reviewId));
        var activeRun = analysisRepository
                .findLatestCompletedRun(organizationId, review.id())
                .orElseThrow(() -> new com.rivell.workbench.review.FindingNotFoundException(
                        findingId));
        findingRepository.findFinding(organizationId, reviewId, activeRun.id(), findingId)
                .orElseThrow(() -> new com.rivell.workbench.review.FindingNotFoundException(
                        findingId));
        return decisionRepository.findHistory(organizationId, reviewId, findingId, limit);
    }

}
