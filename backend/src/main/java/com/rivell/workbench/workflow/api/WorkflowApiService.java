package com.rivell.workbench.workflow.api;

import java.util.List;
import java.util.UUID;

import com.rivell.workbench.organization.AuthorizationService;
import com.rivell.workbench.organization.IdentityProvider;
import com.rivell.workbench.organization.Permissions;
import com.rivell.workbench.redline.ApprovedChange;
import com.rivell.workbench.redline.ApprovedChangeSet;
import com.rivell.workbench.redline.ApprovedChangeSetNotFoundException;
import com.rivell.workbench.workflow.AddFindingCommand;
import com.rivell.workbench.workflow.DecisionCommand;
import com.rivell.workbench.workflow.FinalizationBlocker;
import com.rivell.workbench.workflow.HumanDecision;
import com.rivell.workbench.workflow.HumanDecisionService;
import com.rivell.workbench.workflow.HumanReviewService;
import com.rivell.workbench.workflow.HumanReviewState;
import com.rivell.workbench.workflow.ReviewFinalizationService;
import com.rivell.workbench.workflow.UnauthorizedWorkflowAccessException;
import com.rivell.workbench.workflow.UserFindingService;

/**
 * Human review workflow HTTP surface: decision writes, current decisions and history,
 * derived readiness, explicit finalization, and the frozen approved-change set. Reads
 * require {@code reviews:read}; writes require {@code reviews:decide}. Bounded lists and
 * safe views only.
 */
public class WorkflowApiService {

    private static final int DEFAULT_PAGE_LIMIT = 20;
    private static final int MAX_PAGE_LIMIT = 100;

    private final HumanDecisionService decisionService;
    private final UserFindingService userFindingService;
    private final HumanReviewService humanReviewService;
    private final ReviewFinalizationService finalizationService;
    private final IdentityProvider identityProvider;
    private final AuthorizationService authorizationService;

    public WorkflowApiService(
            HumanDecisionService decisionService,
            UserFindingService userFindingService,
            HumanReviewService humanReviewService,
            ReviewFinalizationService finalizationService,
            IdentityProvider identityProvider,
            AuthorizationService authorizationService) {
        this.decisionService = decisionService;
        this.userFindingService = userFindingService;
        this.humanReviewService = humanReviewService;
        this.finalizationService = finalizationService;
        this.identityProvider = identityProvider;
        this.authorizationService = authorizationService;
    }

    public DecisionView decide(UUID reviewId, UUID findingId, DecisionRequest request) {
        var decision = decisionService.decide(reviewId, findingId, new DecisionCommand(
                request.expectedVersion(), request.clientRequestId(), request.action(),
                request.approvedAction(), request.approvedLanguage(), request.comment(),
                request.rejectReason(), request.approvedBy()));
        return toView(decision);
    }

    public AddedFindingView addFinding(UUID reviewId, AddFindingRequest request) {
        var finding = userFindingService.add(reviewId, new AddFindingCommand(
                request.headingNodeId(), request.severity(), request.note()));
        return new AddedFindingView(finding.id());
    }

    public DecisionListResponse currentDecisions(UUID reviewId, Integer limit) {
        var organizationId = requireRead();
        var bounded = boundedLimit(limit);
        var decisions = humanReviewService.currentDecisions(organizationId, reviewId, bounded);
        var truncated = decisions.size() > bounded;
        return new DecisionListResponse(decisions.stream().limit(bounded)
                .map(WorkflowApiService::toView).toList(), truncated);
    }

    public DecisionListResponse history(UUID reviewId, UUID findingId, Integer limit) {
        var organizationId = requireRead();
        var bounded = boundedLimit(limit);
        var decisions = humanReviewService.history(organizationId, reviewId, findingId, bounded);
        var truncated = decisions.size() > bounded;
        return new DecisionListResponse(decisions.stream().limit(bounded)
                .map(WorkflowApiService::toView).toList(), truncated);
    }

    public HumanReviewView humanReview(UUID reviewId) {
        var organizationId = requireRead();
        var state = humanReviewService.state(organizationId, reviewId);
        var progress = state.assessment().progress();
        var changeSet = state.changeSet();
        return new HumanReviewView(
                state.review().id(),
                progress.status().name(),
                state.review().status().name(),
                progress.actionableFindings(),
                progress.resolvedFindings(),
                progress.remainingFindings(),
                progress.accepted(),
                progress.edited(),
                progress.rejected(),
                progress.escalated(),
                progress.informationalFindings(),
                progress.informationalDecided(),
                progress.readyToFinalize(),
                progress.blockers().stream().map(WorkflowApiService::toView).toList(),
                changeSet.map(ApprovedChangeSet::id).orElse(null),
                changeSet.map(ApprovedChangeSet::finalizedBy).orElse(null),
                changeSet.map(ApprovedChangeSet::finalizedAt).orElse(null));
    }

    public ApprovedChangeSetView finalizeReview(UUID reviewId) {
        return toView(finalizationService.finalizeReview(reviewId));
    }

    public ApprovedChangeSetView approvedChangeSet(UUID reviewId) {
        var organizationId = requireRead();
        return humanReviewService.changeSet(organizationId, reviewId)
                .map(WorkflowApiService::toView)
                .orElseThrow(() -> new ApprovedChangeSetNotFoundException(reviewId));
    }

    private UUID requireRead() {
        var identity = identityProvider.currentIdentity();
        if (!authorizationService.isAuthorized(identity, Permissions.REVIEW_READ)) {
            throw new UnauthorizedWorkflowAccessException(identity.userId());
        }
        return identity.organizationId();
    }

    private static int boundedLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_PAGE_LIMIT;
        }
        if (limit < 1) {
            throw new IllegalArgumentException("The page size must be 1 or more.");
        }
        return Math.min(limit, MAX_PAGE_LIMIT);
    }

    private static DecisionView toView(HumanDecision decision) {
        return new DecisionView(decision.id(), decision.findingId(), decision.revision(),
                decision.action().name(),
                decision.approvedAction() == null ? null : decision.approvedAction().name(),
                decision.approvedLanguage(),
                decision.languageSource() == null ? null : decision.languageSource().name(),
                decision.approvedDraftingEvidenceId(), decision.comment(),
                decision.rejectReason() == null ? null : decision.rejectReason().name(),
                decision.approvedBy(), decision.actorId(),
                decision.decidedAt(), decision.clientRequestId(),
                decision.supersedesDecisionId());
    }

    private static BlockerView toView(FinalizationBlocker blocker) {
        return new BlockerView(blocker.code().name(), blocker.message(), blocker.findingIds());
    }

    private static ApprovedChangeSetView toView(ApprovedChangeSet set) {
        List<ApprovedChangeView> changes = set.changes().stream()
                .map(WorkflowApiService::toView).toList();
        return new ApprovedChangeSetView(set.id(), set.reviewId(), set.contractId(),
                set.contractVersionNumber(), set.runId(), set.changeSetVersion(),
                set.finalizedBy(), set.finalizedAt(), changes);
    }

    private static ApprovedChangeView toView(ApprovedChange change) {
        return new ApprovedChangeView(change.id(), change.findingId(), change.decisionId(),
                change.ordinal(), change.actionType().name(), change.targetKind().name(),
                change.targetHeadingNodeId(),
                change.absenceConcept() == null ? null : change.absenceConcept().value(),
                change.approvedLanguage(), change.languageSource().name(),
                change.approvedDraftingEvidenceId());
    }
}
