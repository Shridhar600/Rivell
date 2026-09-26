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
        // DEGRADED is overloaded: it covers both a check that could not trust its inputs (the
        // analysis environment no longer matches the review snapshot, so every check degrades and
        // the run reports COMPLETED with nothing to show) and a check that ran fine and honestly
        // reported partial coverage. Only the first is a finalization hazard, and it is
        // distinguishable by whether any check completed at all. Counting every DEGRADED as a
        // blocker would make an ordinary partially-covered review impossible to finalize.
        var checkOutcomes = activeRun
                .map(run -> analysisRepository.findExecutions(organizationId, run.id()))
                .orElse(List.of());
        var plannedChecks = reviewRepository.findPlanChecks(organizationId, reviewId).size();
        var completedChecks = (int) checkOutcomes.stream()
                .filter(execution -> execution.status() == CheckExecutionStatus.COMPLETED)
                .count();
        var assessment = HumanReviewResolver.resolve(findings, decisions,
                review.status() == ReviewStatus.COMPLETED && activeRun.isPresent(),
                plannedChecks > 0 && completedChecks == 0, changeSet.isPresent(), headings);
        return new HumanReviewState(review, activeRun.map(run -> run.id()).orElse(null),
                findings, decisions, changeSet, assessment);
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
