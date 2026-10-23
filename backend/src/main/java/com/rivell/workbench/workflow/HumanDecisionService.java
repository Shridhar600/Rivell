package com.rivell.workbench.workflow;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.transaction.support.TransactionTemplate;

import com.rivell.workbench.contracts.ContractQueryService;
import com.rivell.workbench.organization.AuthorizationService;
import com.rivell.workbench.organization.IdentityProvider;
import com.rivell.workbench.organization.Permissions;
import com.rivell.workbench.redline.ApprovedChangeSetRepository;
import com.rivell.workbench.redline.ChangeActionType;
import com.rivell.workbench.redline.ChangeTargetKind;
import com.rivell.workbench.review.FindingNotFoundException;
import com.rivell.workbench.review.FindingRepository;
import com.rivell.workbench.review.ReviewAnalysisRepository;
import com.rivell.workbench.review.ReviewNotFoundException;
import com.rivell.workbench.review.ReviewRepository;
import com.rivell.workbench.review.ReviewStatus;

/**
 * Human decision writes: authorization, target validation against the active completed
 * run, typed action validation, optimistic concurrency, and idempotent replay. All writes
 * serialize on the review row so decisions can neither race each other nor commit after a
 * finalization. Revisions are append-only; no M7 machine state is touched.
 */
public class HumanDecisionService {

    private final IdentityProvider identityProvider;
    private final AuthorizationService authorizationService;
    private final ReviewRepository reviewRepository;
    private final ReviewAnalysisRepository analysisRepository;
    private final FindingRepository findingRepository;
    private final HumanDecisionRepository decisionRepository;
    private final ApprovedChangeSetRepository changeSetRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final ContractQueryService contractQueryService;

    public HumanDecisionService(
            IdentityProvider identityProvider,
            AuthorizationService authorizationService,
            ReviewRepository reviewRepository,
            ReviewAnalysisRepository analysisRepository,
            FindingRepository findingRepository,
            HumanDecisionRepository decisionRepository,
            ApprovedChangeSetRepository changeSetRepository,
            TransactionTemplate transactionTemplate,
            Clock clock,
            ContractQueryService contractQueryService) {
        this.identityProvider = identityProvider;
        this.authorizationService = authorizationService;
        this.reviewRepository = reviewRepository;
        this.analysisRepository = analysisRepository;
        this.findingRepository = findingRepository;
        this.decisionRepository = decisionRepository;
        this.changeSetRepository = changeSetRepository;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
        this.contractQueryService = contractQueryService;
    }

    public HumanDecision decide(UUID reviewId, UUID findingId, DecisionCommand command) {
        var identity = identityProvider.currentIdentity();
        if (!authorizationService.isAuthorized(identity, Permissions.REVIEW_DECIDE)) {
            throw new UnauthorizedWorkflowAccessException(identity.userId());
        }
        var organizationId = identity.organizationId();
        var review = reviewRepository.find(organizationId, reviewId)
                .orElseThrow(() -> new ReviewNotFoundException(reviewId));
        requireExpectedVersion(command);
        if (review.status() != ReviewStatus.COMPLETED) {
            throw new AnalysisNotCompleteException(reviewId);
        }
        var activeRun = analysisRepository.findLatestCompletedRun(organizationId, reviewId)
                .orElseThrow(() -> new AnalysisNotCompleteException(reviewId));
        var finding = findingRepository
                .findFinding(organizationId, reviewId, activeRun.id(), findingId)
                .orElseThrow(() -> new FindingNotFoundException(findingId));
        DecisionRules.validateRequest(command.action(), command.approvedAction(),
                command.approvedLanguage(), command.comment());
        var decisionId = UUID.randomUUID();
        var planned = command.action() == DecisionAction.ACCEPT
                || command.action() == DecisionAction.EDIT
                        ? DecisionRules.plan(finding, command.action(),
                                command.approvedAction(), command.approvedLanguage(), decisionId)
                        : null;
        if (planned != null && planned.targetKind() == ChangeTargetKind.CLAUSE
                && planned.actionType() != ChangeActionType.INSERT
                && contractQueryService.untrackableHeadingsForOrganization(organizationId,
                        review.contractId(), review.contractVersionNumber())
                        .contains(planned.targetHeadingNodeId())) {
            throw new UntrackableChangeTargetException(findingId);
        }

        return transactionTemplate.execute(status -> {
            if (!reviewRepository.lock(organizationId, reviewId)) {
                throw new ReviewNotFoundException(reviewId);
            }
            var currentRun = analysisRepository.findLatestCompletedRun(organizationId, reviewId);
            if (currentRun.isEmpty() || !currentRun.get().id().equals(activeRun.id())) {
                throw new AnalysisNotCompleteException(reviewId);
            }
            if (command.clientRequestId() != null) {
                var replay = decisionRepository.findByClientRequestId(
                        organizationId, findingId, command.clientRequestId());
                if (replay.isPresent()) {
                    return replay.get();
                }
            }
            if (changeSetRepository.findByReview(organizationId, reviewId).isPresent()) {
                throw new ReviewAlreadyFinalizedException(reviewId);
            }
            var current = decisionRepository.findCurrent(organizationId, reviewId, findingId);
            var currentRevision = current.map(HumanDecision::revision).orElse(0);
            if (command.expectedVersion() != currentRevision) {
                throw new DecisionConflictException(findingId, command.expectedVersion(),
                        currentRevision);
            }
            // Lowered to the precision PostgreSQL keeps for timestamptz: a nanosecond value would
            // be stored rounded, so the returned decision would not match a later read of it.
            var decision = new HumanDecision(decisionId, organizationId, reviewId, findingId,
                    activeRun.id(), currentRevision + 1, command.action(),
                    planned == null ? null : planned.actionType(),
                    language(planned), source(planned), draftingEvidence(planned),
                    command.comment(), identity.userId(),
                    clock.instant().truncatedTo(ChronoUnit.MICROS),
                    command.clientRequestId(), current.map(HumanDecision::id).orElse(null));
            if (!decisionRepository.insert(decision)) {
                throw new DecisionConflictException(findingId, command.expectedVersion(),
                        currentRevision);
            }
            return decision;
        });
    }

    private static String language(PlannedChange planned) {
        return planned == null || planned.actionType() == ChangeActionType.DELETE
                ? null : planned.approvedLanguage();
    }

    private static com.rivell.workbench.redline.ChangeLanguageSource source(
            PlannedChange planned) {
        return planned == null || planned.actionType() == ChangeActionType.DELETE
                ? null : planned.languageSource();
    }

    private static UUID draftingEvidence(PlannedChange planned) {
        return planned == null || planned.actionType() == ChangeActionType.DELETE
                ? null : planned.approvedDraftingEvidenceId();
    }

    private static void requireExpectedVersion(DecisionCommand command) {
        if (command.expectedVersion() == null || command.expectedVersion() < 0) {
            throw new InvalidDecisionException(
                    "Reload the review before you decide. Rivell needs the current version of "
                            + "this finding.");
        }
    }
}
