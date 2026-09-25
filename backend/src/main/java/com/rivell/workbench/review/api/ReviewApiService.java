package com.rivell.workbench.review.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.rivell.workbench.contracts.ContractQueryService;
import com.rivell.workbench.contracts.LegalConcept;
import com.rivell.workbench.organization.AuthorizationService;
import com.rivell.workbench.organization.IdentityProvider;
import com.rivell.workbench.organization.Permissions;
import com.rivell.workbench.review.CheckExecutionStatus;
import com.rivell.workbench.review.Finding;
import com.rivell.workbench.review.FindingClassification;
import com.rivell.workbench.review.FindingNotFoundException;
import com.rivell.workbench.review.FindingRepository;
import com.rivell.workbench.review.FindingSeverity;
import com.rivell.workbench.review.FindingSubjectKind;
import com.rivell.workbench.review.Review;
import com.rivell.workbench.review.ReviewAnalysisRepository;
import com.rivell.workbench.review.ReviewAnalysisRun;
import com.rivell.workbench.review.ReviewAnalysisService;
import com.rivell.workbench.review.ReviewCheckExecution;
import com.rivell.workbench.review.ReviewNotFoundException;
import com.rivell.workbench.review.ReviewRepository;
import com.rivell.workbench.review.ReviewService;
import com.rivell.workbench.review.UnauthorizedReviewAccessException;

/**
 * Composes the review HTTP surface from domain read models and ports: authorization,
 * active-run resolution, bounded lists, and view mapping. Read endpoints require
 * {@code reviews:read}; creation requires {@code reviews:create} (enforced by
 * {@link ReviewService}) and listing also resolves the contract (transitive
 * {@code contracts:read}, matching the creation flow). Cross-organization resources
 * are missing.
 */
public class ReviewApiService {

    private static final int DEFAULT_PAGE_LIMIT = 20;
    private static final int MAX_PAGE_LIMIT = 100;
    private static final int MAX_ANALYSIS_WARNINGS = 20;
    private static final int MAX_REASON_LENGTH = 300;

    private final ReviewService reviewService;
    private final ReviewAnalysisService analysisService;
    private final ReviewRepository reviewRepository;
    private final ReviewAnalysisRepository analysisRepository;
    private final FindingRepository findingRepository;
    private final ContractQueryService contractQueryService;
    private final IdentityProvider identityProvider;
    private final AuthorizationService authorizationService;

    public ReviewApiService(
            ReviewService reviewService,
            ReviewAnalysisService analysisService,
            ReviewRepository reviewRepository,
            ReviewAnalysisRepository analysisRepository,
            FindingRepository findingRepository,
            ContractQueryService contractQueryService,
            IdentityProvider identityProvider,
            AuthorizationService authorizationService) {
        this.reviewService = reviewService;
        this.analysisService = analysisService;
        this.reviewRepository = reviewRepository;
        this.analysisRepository = analysisRepository;
        this.findingRepository = findingRepository;
        this.contractQueryService = contractQueryService;
        this.identityProvider = identityProvider;
        this.authorizationService = authorizationService;
    }

    public ReviewSummaryView create(
            UUID contractId, int versionNumber, String requestedPackKey) {
        var review = reviewService.create(contractId, versionNumber, requestedPackKey);
        return summaryFor(review.organizationId(), review.id());
    }

    public ReviewSummaryView summary(UUID reviewId) {
        return summaryFor(requireRead(), reviewId);
    }

    public ReviewProgressView progress(UUID reviewId) {
        var organizationId = requireRead();
        var progress = analysisService.progress(organizationId, reviewId);
        var warnings = warnings(organizationId, reviewId,
                countedRun(organizationId, reviewId));
        return new ReviewProgressView(
                progress.reviewStatus().name(),
                progress.plannedChecks(),
                progress.pendingChecks(),
                progress.runningChecks(),
                progress.completedChecks(),
                progress.degradedChecks(),
                progress.failedChecks(),
                progress.skippedChecks(),
                progress.signals(),
                progress.findings(),
                progress.latestRunStatus() == null ? null : progress.latestRunStatus().name(),
                warnings.items(),
                warnings.truncated());
    }

    public ReviewListResponse list(UUID contractId, Integer limit) {
        var organizationId = requireRead();
        contractQueryService.getContract(contractId);
        var bounded = boundedLimit(limit);
        var rows = reviewRepository.findReviews(organizationId, contractId, bounded);
        var truncated = rows.size() > bounded;
        var items = rows.stream().limit(bounded).map(ReviewApiService::toListItem).toList();
        return new ReviewListResponse(items, truncated);
    }

    public FindingListResponse findings(
            UUID reviewId, LegalConcept concept, FindingClassification classification,
            FindingSeverity severity, Integer limit) {
        var organizationId = requireRead();
        requireReview(organizationId, reviewId);
        var activeRun = analysisRepository.findLatestCompletedRun(organizationId, reviewId);
        if (activeRun.isEmpty()) {
            return new FindingListResponse(List.of(), false);
        }
        var bounded = boundedLimit(limit);
        var rows = findingRepository.findFindings(organizationId, activeRun.get().id(),
                concept, classification, severity, bounded);
        var truncated = rows.size() > bounded;
        var items = rows.stream().limit(bounded).map(ReviewApiService::toSummary).toList();
        return new FindingListResponse(items, truncated);
    }

    public FindingDetailView finding(UUID reviewId, UUID findingId) {
        var organizationId = requireRead();
        requireReview(organizationId, reviewId);
        var activeRun = analysisRepository.findLatestCompletedRun(organizationId, reviewId)
                .orElseThrow(() -> new FindingNotFoundException(findingId));
        var finding = findingRepository
                .findFinding(organizationId, reviewId, activeRun.id(), findingId)
                .orElseThrow(() -> new FindingNotFoundException(findingId));
        var absenceBasis = finding.subjectKind() == FindingSubjectKind.ABSENCE
                ? findingRepository.findAbsenceBasis(organizationId, activeRun.id(), findingId)
                        .map(basis -> new FindingDetailView.AbsenceBasisView(
                                basis.planCheckId(), basis.checkKey()))
                        .orElse(null)
                : null;
        return toDetail(finding, absenceBasis);
    }

    private ReviewSummaryView summaryFor(UUID organizationId, UUID reviewId) {
        var review = reviewRepository.find(organizationId, reviewId)
                .orElseThrow(() -> new ReviewNotFoundException(reviewId));
        var snapshot = reviewRepository.findSnapshot(organizationId, reviewId)
                .orElseThrow(() -> new IllegalStateException(
                        "Review snapshot missing: " + reviewId));
        var progress = analysisService.progress(organizationId, reviewId);
        var activeRun = analysisRepository.findLatestCompletedRun(organizationId, reviewId);
        var latestRun = analysisRepository.findLatestRun(organizationId, reviewId);
        var latestRunStatus = latestRun
                .filter(run -> activeRun
                        .map(active -> !active.id().equals(run.id())).orElse(true))
                .map(run -> run.status().name())
                .orElse(null);
        var warnings = warnings(organizationId, reviewId, activeRun.or(() -> latestRun));
        return new ReviewSummaryView(
                review.id(),
                review.contractId(),
                review.contractVersionNumber(),
                review.status().name(),
                review.failureReason(),
                review.createdAt(),
                review.startedAt(),
                review.completedAt(),
                activeRun.map(run -> new ReviewRunView(
                        run.id(), run.runNumber(), run.status().name())).orElse(null),
                latestRunStatus,
                progress.plannedChecks(),
                progress.completedChecks(),
                progress.degradedChecks(),
                progress.failedChecks(),
                progress.signals(),
                progress.findings(),
                snapshot.contractPackKey(),
                snapshot.packVersion(),
                snapshot.knowledgePinNumber(),
                warnings.items(),
                warnings.truncated());
    }

    /**
     * Warnings describe the run that {@link ReviewAnalysisService#progress} counts (the
     * latest COMPLETED run, else the latest run): the generation whose results a
     * consumer is inspecting. Bounded and sanitized.
     */
    private Warnings warnings(
            UUID organizationId, UUID reviewId, Optional<ReviewAnalysisRun> countedRun) {
        if (countedRun.isEmpty()) {
            return new Warnings(List.of(), false);
        }
        var checkKeys = new HashMap<UUID, String>();
        for (var check : reviewRepository.findPlanChecks(organizationId, reviewId)) {
            checkKeys.put(check.id(), check.checkKey());
        }
        var all = new ArrayList<AnalysisWarningView>();
        for (var execution : analysisRepository.findExecutions(
                organizationId, countedRun.get().id())) {
            if (execution.status() != CheckExecutionStatus.DEGRADED
                    && execution.status() != CheckExecutionStatus.FAILED) {
                continue;
            }
            all.add(new AnalysisWarningView(
                    checkKeys.getOrDefault(execution.planCheckId(), "UNKNOWN"),
                    execution.status().name(),
                    sanitizeReason(execution)));
        }
        var items = all.size() > MAX_ANALYSIS_WARNINGS
                ? List.copyOf(all.subList(0, MAX_ANALYSIS_WARNINGS))
                : List.copyOf(all);
        return new Warnings(items, all.size() > MAX_ANALYSIS_WARNINGS);
    }

    private static String sanitizeReason(ReviewCheckExecution execution) {
        var reason = execution.detail().get("degradedReason");
        if (reason == null) {
            reason = execution.detail().get("failureReason");
        }
        if (reason == null) {
            return null;
        }
        var text = reason.toString().replaceAll("\\p{Cntrl}", " ")
                .replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) {
            return null;
        }
        return text.length() > MAX_REASON_LENGTH
                ? text.substring(0, MAX_REASON_LENGTH)
                : text;
    }

    private UUID requireRead() {
        var identity = identityProvider.currentIdentity();
        if (!authorizationService.isAuthorized(identity, Permissions.REVIEW_READ)) {
            throw new UnauthorizedReviewAccessException(identity.userId());
        }
        return identity.organizationId();
    }

    private void requireReview(UUID organizationId, UUID reviewId) {
        if (reviewRepository.find(organizationId, reviewId).isEmpty()) {
            throw new ReviewNotFoundException(reviewId);
        }
    }

    private Optional<ReviewAnalysisRun> countedRun(UUID organizationId, UUID reviewId) {
        return analysisRepository.findLatestCompletedRun(organizationId, reviewId)
                .or(() -> analysisRepository.findLatestRun(organizationId, reviewId));
    }

    private static int boundedLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_PAGE_LIMIT;
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1");
        }
        return Math.min(limit, MAX_PAGE_LIMIT);
    }

    private static ReviewListItemView toListItem(Review review) {
        return new ReviewListItemView(review.id(), review.contractId(),
                review.contractVersionNumber(), review.status().name(), review.createdAt(),
                review.completedAt());
    }

    private static FindingSummaryView toSummary(Finding finding) {
        return new FindingSummaryView(finding.id(), finding.subjectKind(),
                finding.subjectHeadingNodeId(), finding.absenceConcept(), finding.concept(),
                finding.secondaryConcept(), finding.issueFamily(), finding.observationKind(),
                finding.severity(), finding.reviewClassification(), finding.proposedAction(),
                finding.proposedLanguageSource(), finding.createdAt());
    }

    private static FindingDetailView toDetail(
            Finding finding, FindingDetailView.AbsenceBasisView absenceBasis) {
        return new FindingDetailView(finding.id(), finding.reviewId(), finding.runId(),
                finding.subjectKind(), finding.subjectHeadingNodeId(),
                finding.absenceConcept(), finding.concept(), finding.secondaryConcept(),
                finding.concepts(), finding.issueFamily(), finding.observationKind(),
                finding.observationText(), finding.severity(),
                finding.reviewClassification(), finding.proposedAction(),
                finding.proposedLanguage(), finding.proposedLanguageSource(),
                finding.approvedDraftingEvidenceId(), finding.quality(),
                finding.claims().stream()
                        .map(claim -> new FindingDetailView.ClaimView(claim.ordinal(),
                                claim.claimKind(), claim.text(), claim.absence(),
                                claim.evidenceItemIds()))
                        .toList(),
                finding.evidenceItemIds(), finding.signalIds(), absenceBasis,
                finding.createdAt());
    }

    private record Warnings(List<AnalysisWarningView> items, boolean truncated) {
    }
}
