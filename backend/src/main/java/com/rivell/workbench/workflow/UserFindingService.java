package com.rivell.workbench.workflow;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.transaction.support.TransactionTemplate;

import com.rivell.workbench.organization.AuthorizationService;
import com.rivell.workbench.organization.IdentityProvider;
import com.rivell.workbench.organization.Permissions;
import com.rivell.workbench.redline.ApprovedChangeSetRepository;
import com.rivell.workbench.review.Finding;
import com.rivell.workbench.review.FindingClassification;
import com.rivell.workbench.review.FindingRepository;
import com.rivell.workbench.review.FindingSeverity;
import com.rivell.workbench.review.FindingSubjectKind;
import com.rivell.workbench.review.IssueFamily;
import com.rivell.workbench.review.ProposedAction;
import com.rivell.workbench.review.ProposedLanguageSource;
import com.rivell.workbench.review.ReviewAnalysisRepository;
import com.rivell.workbench.review.ReviewNotFoundException;
import com.rivell.workbench.review.ReviewRepository;
import com.rivell.workbench.review.ReviewStatus;

/**
 * A person adds a finding Rivell missed (design 2026-10-12 §9). The addition is an ordinary
 * finding of the active completed run, review-required, with no proposal, so the person decides
 * it like any other: EDIT to change the clause, REJECT (with a reason) to withdraw it. The same
 * guards as a decision apply, under the same review-row lock, so an addition can neither race a
 * finalization nor land on a run that is no longer active.
 */
public class UserFindingService {

    private final IdentityProvider identityProvider;
    private final AuthorizationService authorizationService;
    private final ReviewRepository reviewRepository;
    private final ReviewAnalysisRepository analysisRepository;
    private final FindingRepository findingRepository;
    private final ApprovedChangeSetRepository changeSetRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public UserFindingService(
            IdentityProvider identityProvider,
            AuthorizationService authorizationService,
            ReviewRepository reviewRepository,
            ReviewAnalysisRepository analysisRepository,
            FindingRepository findingRepository,
            ApprovedChangeSetRepository changeSetRepository,
            TransactionTemplate transactionTemplate,
            Clock clock) {
        this.identityProvider = identityProvider;
        this.authorizationService = authorizationService;
        this.reviewRepository = reviewRepository;
        this.analysisRepository = analysisRepository;
        this.findingRepository = findingRepository;
        this.changeSetRepository = changeSetRepository;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    public Finding add(UUID reviewId, AddFindingCommand command) {
        var identity = identityProvider.currentIdentity();
        if (!authorizationService.isAuthorized(identity, Permissions.REVIEW_DECIDE)) {
            throw new UnauthorizedWorkflowAccessException(identity.userId());
        }
        var organizationId = identity.organizationId();
        var review = reviewRepository.find(organizationId, reviewId)
                .orElseThrow(() -> new ReviewNotFoundException(reviewId));
        var note = command.note() == null ? "" : command.note().strip();
        if (note.isEmpty()) {
            throw new InvalidDecisionException("Add a note that says what Rivell missed.");
        }
        if (note.length() > DecisionRules.MAX_COMMENT_LENGTH) {
            throw new InvalidDecisionException(
                    "The note is too long. Keep it to " + DecisionRules.MAX_COMMENT_LENGTH
                            + " characters or fewer.");
        }
        if (command.headingNodeId() == null) {
            throw new InvalidDecisionException("Choose the clause this finding is about.");
        }
        var severity = command.severity() == null ? FindingSeverity.MEDIUM : command.severity();
        if (review.status() != ReviewStatus.COMPLETED) {
            throw new AnalysisNotCompleteException(reviewId);
        }
        var activeRun = analysisRepository.findLatestCompletedRun(organizationId, reviewId)
                .orElseThrow(() -> new AnalysisNotCompleteException(reviewId));
        var planned = reviewRepository.findPlanChecks(organizationId, reviewId).stream()
                .filter(check -> command.headingNodeId().equals(check.subjectHeadingNodeId()))
                .findFirst()
                .orElseThrow(() -> new InvalidDecisionException(
                        "Rivell did not check that clause in this review."));

        return transactionTemplate.execute(status -> {
            if (!reviewRepository.lock(organizationId, reviewId)) {
                throw new ReviewNotFoundException(reviewId);
            }
            var currentRun = analysisRepository.findLatestCompletedRun(organizationId, reviewId);
            if (currentRun.isEmpty() || !currentRun.get().id().equals(activeRun.id())) {
                throw new AnalysisNotCompleteException(reviewId);
            }
            if (changeSetRepository.findByReview(organizationId, reviewId).isPresent()) {
                throw new ReviewAlreadyFinalizedException(reviewId);
            }
            var id = UUID.randomUUID();
            // Lowered to the precision PostgreSQL keeps for timestamptz: a nanosecond value would
            // be stored rounded, so the returned finding would not match a later read of it.
            var finding = new Finding(id, organizationId, reviewId, activeRun.id(), "user:" + id,
                    FindingSubjectKind.CLAUSE, command.headingNodeId(), null, planned.concept(),
                    null, IssueFamily.USER_ADDED, IssueFamily.USER_ADDED.name(), note, severity,
                    FindingClassification.REVIEW_REQUIRED, ProposedAction.NO_ACTION, null,
                    ProposedLanguageSource.NONE, null, Map.of(),
                    clock.instant().truncatedTo(ChronoUnit.MICROS),
                    List.of(planned.concept()), List.of(), List.of(), List.of(),
                    identity.userId());
            findingRepository.addFinding(finding);
            return finding;
        });
    }
}
