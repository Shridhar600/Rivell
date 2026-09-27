package com.rivell.workbench.workflow;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.UUID;

import org.springframework.transaction.support.TransactionTemplate;

import com.rivell.workbench.organization.AuthorizationService;
import com.rivell.workbench.organization.IdentityProvider;
import com.rivell.workbench.organization.Permissions;
import com.rivell.workbench.redline.ApprovedChange;
import com.rivell.workbench.redline.ApprovedChangeSet;
import com.rivell.workbench.redline.ApprovedChangeSetRepository;
import com.rivell.workbench.review.Finding;
import com.rivell.workbench.review.FindingSeverity;
import com.rivell.workbench.review.ReviewNotFoundException;
import com.rivell.workbench.review.ReviewRepository;

/**
 * Explicit review finalization: serializes with decision writes on the review row,
 * re-validates readiness inside the transaction, derives the deterministic approved
 * changes, and freezes the immutable M8 → M9 artifact. Idempotent — a second finalize
 * returns the existing set.
 */
public class ReviewFinalizationService {

    private final IdentityProvider identityProvider;
    private final AuthorizationService authorizationService;
    private final ReviewRepository reviewRepository;
    private final HumanReviewService humanReviewService;
    private final ApprovedChangeSetRepository changeSetRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public ReviewFinalizationService(
            IdentityProvider identityProvider,
            AuthorizationService authorizationService,
            ReviewRepository reviewRepository,
            HumanReviewService humanReviewService,
            ApprovedChangeSetRepository changeSetRepository,
            TransactionTemplate transactionTemplate,
            Clock clock) {
        this.identityProvider = identityProvider;
        this.authorizationService = authorizationService;
        this.reviewRepository = reviewRepository;
        this.humanReviewService = humanReviewService;
        this.changeSetRepository = changeSetRepository;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    public ApprovedChangeSet finalizeReview(UUID reviewId) {
        var identity = identityProvider.currentIdentity();
        if (!authorizationService.isAuthorized(identity, Permissions.REVIEW_DECIDE)) {
            throw new UnauthorizedWorkflowAccessException(identity.userId());
        }
        var organizationId = identity.organizationId();
        var finalizedBy = identity.userId();
        return transactionTemplate.execute(status -> {
            if (!reviewRepository.lock(organizationId, reviewId)) {
                throw new ReviewNotFoundException(reviewId);
            }
            var existing = changeSetRepository.findByReview(organizationId, reviewId);
            if (existing.isPresent()) {
                return existing.get();
            }
            var state = humanReviewService.state(organizationId, reviewId);
            var progress = state.assessment().progress();
            if (!progress.readyToFinalize()) {
                throw new ReviewNotFinalizableException(progress.blockers());
            }
            // Freeze the semantics digest the review planned against. M9 derives INSERT
            // placement from effective concepts and must be able to prove it is still using
            // the classification the human approved changes against.
            var snapshot = reviewRepository.findSnapshot(organizationId, reviewId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Review snapshot missing: " + reviewId));
            var set = buildSet(organizationId, state, snapshot.semanticsDigest(), finalizedBy);
            if (!changeSetRepository.freeze(set)) {
                return changeSetRepository.findByReview(organizationId, reviewId)
                        .orElseThrow(() -> new IllegalStateException(
                                "Finalization lost the race but no change set exists"));
            }
            return set;
        });
    }

    private ApprovedChangeSet buildSet(UUID organizationId, HumanReviewState state,
            String semanticsDigest, UUID finalizedBy) {
        var review = state.review();
        var changeSetId = UUID.randomUUID();
        var severityByFinding = new HashMap<UUID, FindingSeverity>();
        for (Finding finding : state.findings()) {
            severityByFinding.put(finding.id(), finding.severity());
        }
        var ordered = new ArrayList<>(state.assessment().plannedChanges());
        ordered.sort(Comparator
                .comparingInt((PlannedChange change) -> severityRank(
                        severityByFinding.get(change.findingId())))
                .thenComparing(change -> change.findingId().toString()));
        var changes = new ArrayList<ApprovedChange>();
        for (int ordinal = 0; ordinal < ordered.size(); ordinal++) {
            var planned = ordered.get(ordinal);
            changes.add(new ApprovedChange(UUID.randomUUID(), organizationId, changeSetId,
                    review.id(), ordinal, planned.findingId(), planned.decisionId(),
                    planned.actionType(), planned.targetKind(), planned.targetHeadingNodeId(),
                    planned.absenceConcept(), planned.approvedLanguage(),
                    planned.languageSource(), planned.approvedDraftingEvidenceId()));
        }
        return new ApprovedChangeSet(changeSetId, organizationId, review.id(),
                state.activeRunId(), review.contractId(), review.contractVersionId(),
                review.contractVersionNumber(), 1, semanticsDigest, finalizedBy,
                clock.instant(), changes);
    }

    private static int severityRank(FindingSeverity severity) {
        return severity == FindingSeverity.HIGH ? 0
                : severity == FindingSeverity.MEDIUM ? 1 : 2;
    }
}
