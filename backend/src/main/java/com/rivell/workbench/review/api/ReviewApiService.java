package com.rivell.workbench.review.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.rivell.workbench.contracts.ContractQueryService;
import com.rivell.workbench.contracts.LegalConcept;
import com.rivell.workbench.organization.AuthorizationService;
import com.rivell.workbench.organization.IdentityProvider;
import com.rivell.workbench.organization.Permissions;
import com.rivell.workbench.retrieval.EvidenceItem;
import com.rivell.workbench.retrieval.api.RetrievalResponses;
import com.rivell.workbench.review.CheckExecutionStatus;
import com.rivell.workbench.review.CheckMechanism;
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
import com.rivell.workbench.review.ReviewEvidenceIntegrityException;
import com.rivell.workbench.review.ReviewNotFoundException;
import com.rivell.workbench.review.ReviewPackRegistry;
import com.rivell.workbench.review.ReviewPlanCheck;
import com.rivell.workbench.review.ReviewRepository;
import com.rivell.workbench.review.ReviewService;
import com.rivell.workbench.review.UnauthorizedReviewAccessException;

/**
 * Composes the review HTTP surface from domain read models and ports: authorization,
 * active-run resolution, bounded lists, frozen-context and coverage mapping, and
 * resolution of the exact frozen evidence items a Finding cites. Read endpoints require
 * {@code reviews:read}; creation requires {@code reviews:create} (enforced by
 * {@link ReviewService}) and listing also resolves the contract (transitive
 * {@code contracts:read}). Cross-organization resources are missing.
 */
public class ReviewApiService {

    private static final int DEFAULT_PAGE_LIMIT = 20;
    private static final int MAX_PAGE_LIMIT = 100;
    private static final int MAX_ANALYSIS_WARNINGS = 20;

    private final ReviewService reviewService;
    private final ReviewAnalysisService analysisService;
    private final ReviewRepository reviewRepository;
    private final ReviewAnalysisRepository analysisRepository;
    private final FindingRepository findingRepository;
    private final ContractQueryService contractQueryService;
    private final ReviewPackRegistry reviewPackRegistry;
    private final IdentityProvider identityProvider;
    private final AuthorizationService authorizationService;

    public ReviewApiService(
            ReviewService reviewService,
            ReviewAnalysisService analysisService,
            ReviewRepository reviewRepository,
            ReviewAnalysisRepository analysisRepository,
            FindingRepository findingRepository,
            ContractQueryService contractQueryService,
            ReviewPackRegistry reviewPackRegistry,
            IdentityProvider identityProvider,
            AuthorizationService authorizationService) {
        this.reviewService = reviewService;
        this.analysisService = analysisService;
        this.reviewRepository = reviewRepository;
        this.analysisRepository = analysisRepository;
        this.findingRepository = findingRepository;
        this.contractQueryService = contractQueryService;
        this.reviewPackRegistry = reviewPackRegistry;
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

    public ReviewPackCatalogView packs() {
        requireRead();
        var packs = reviewPackRegistry.all().stream()
                .map(pack -> new ReviewPackCatalogView.PackView(pack.key(),
                        pack.displayName(), pack.summary(), pack.version()))
                .toList();
        return new ReviewPackCatalogView(packs);
    }

    public ReviewProgressView progress(UUID reviewId) {
        var organizationId = requireRead();
        var progress = analysisService.progress(organizationId, reviewId);
        var counted = countedRun(organizationId, reviewId);
        var warnings = warnings(organizationId, reviewId, counted);
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
                coverage(organizationId, reviewId, counted),
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
        var evidenceItems = resolveEvidenceItems(organizationId, activeRun.id(), finding);
        return toDetail(finding, evidenceItems, absenceBasis);
    }

    /**
     * Resolves the exact frozen items the finding cites from its own run. Every claim
     * evidence id must be part of the finding's evidence set and must resolve; anything
     * else is an internal integrity failure, never a silently dropped reference.
     */
    private List<RetrievalResponses.EvidenceItemResponse> resolveEvidenceItems(
            UUID organizationId, UUID runId, Finding finding) {
        for (var claim : finding.claims()) {
            for (var evidenceItemId : claim.evidenceItemIds()) {
                if (!finding.evidenceItemIds().contains(evidenceItemId)) {
                    throw new ReviewEvidenceIntegrityException(finding.id());
                }
            }
        }
        if (finding.evidenceItemIds().isEmpty()) {
            return List.of();
        }
        var resolved = analysisRepository.findEvidenceItems(
                organizationId, runId, finding.evidenceItemIds());
        var byId = new LinkedHashMap<UUID, EvidenceItem>();
        resolved.forEach(item -> byId.put(item.evidenceId(), item));
        var ordered = new ArrayList<RetrievalResponses.EvidenceItemResponse>();
        for (var evidenceItemId : finding.evidenceItemIds()) {
            var item = byId.get(evidenceItemId);
            if (item == null) {
                throw new ReviewEvidenceIntegrityException(finding.id());
            }
            ordered.add(RetrievalResponses.evidenceItem(item));
        }
        return List.copyOf(ordered);
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
        var counted = activeRun.or(() -> latestRun);
        var warnings = warnings(organizationId, reviewId, counted);
        return new ReviewSummaryView(
                review.id(),
                review.contractId(),
                review.contractVersionNumber(),
                review.status().name(),
                failure(review.failureReason()),
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
                new ReviewSummaryView.SnapshotView(
                        new ReviewSummaryView.ProfileView(
                                snapshot.contractType().name(),
                                snapshot.ourSideRole() == null ? null
                                        : snapshot.ourSideRole().name(),
                                snapshot.paperSource() == null ? null
                                        : snapshot.paperSource().name(),
                                snapshot.jurisdiction().name(),
                                snapshot.contractPackKey()),
                        snapshot.packVersion(),
                        snapshot.knowledgePinNumber(),
                        snapshot.effectiveOn()),
                coverage(organizationId, reviewId, counted),
                warnings.items(),
                warnings.truncated());
    }

    /**
     * Warnings describe the run that {@link ReviewAnalysisService#progress} counts (the
     * latest COMPLETED run, else the latest run): the generation whose results a
     * consumer is inspecting. Bounded, with code-owned reason codes and safe messages.
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
            all.add(warning(checkKeys.getOrDefault(execution.planCheckId(), "UNKNOWN"),
                    execution));
        }
        var items = all.size() > MAX_ANALYSIS_WARNINGS
                ? List.copyOf(all.subList(0, MAX_ANALYSIS_WARNINGS))
                : List.copyOf(all);
        return new Warnings(items, all.size() > MAX_ANALYSIS_WARNINGS);
    }

    private static AnalysisWarningView warning(
            String checkKey, ReviewCheckExecution execution) {
        var raw = execution.detail().get("degradedReason");
        if (raw == null) {
            raw = execution.detail().get("failureReason");
        }
        var reason = warningReason(raw == null ? null : raw.toString());
        if (execution.status() == CheckExecutionStatus.FAILED
                && !"DECISION_CONTRACT_ERROR".equals(reason.code())) {
            reason = new WarningReason("CHECK_FAILED",
                    "This check failed unexpectedly; internal diagnostics were recorded.");
        }
        return new AnalysisWarningView(checkKey, execution.status().name(),
                reason.code(), reason.message());
    }

    private record WarningReason(String code, String message) {
    }

    /** Maps internal execution reasons to stable public codes; never leaks internals. */
    private static WarningReason warningReason(String raw) {
        if (raw == null || raw.isBlank()) {
            return new WarningReason("CHECK_INCOMPLETE",
                    "This check could not complete and was recorded as a limitation.");
        }
        var text = raw.trim();
        var prefix = text.contains(":") ? text.substring(0, text.indexOf(':')) : text;
        return switch (prefix) {
            case "provider_unavailable" -> new WarningReason("PROVIDER_UNAVAILABLE",
                    "An external analysis provider was unavailable for this check.");
            case "open_ended_generation_unavailable" -> new WarningReason(
                    "GENERATION_UNAVAILABLE",
                    "Candidate language could not be generated for this check.");
            case "classification_uncertain", "cross_clause_classification_uncertain" ->
                    new WarningReason("CLASSIFICATION_UNCERTAIN",
                            "The bounded classification was not confident enough to trust.");
            case "carve_out_context_too_large" -> new WarningReason("CONTEXT_TOO_LARGE",
                    "The clause context was too large for a bounded assessment.");
            case "unexpected_deviation_type", "unexpected_consistency_answer" ->
                    new WarningReason("UNEXPECTED_RESULT",
                            "The analysis returned a result the engine does not accept.");
            case "subject_clause_not_found" -> new WarningReason("SUBJECT_CLAUSE_MISSING",
                    "The reviewed clause was not found in the frozen contract structure.");
            case "counterpart_clause_not_found" -> new WarningReason(
                    "COUNTERPART_CLAUSE_MISSING",
                    "The counterpart clause was not found in the frozen contract structure.");
            case "ambiguity_without_multiple_rules" -> new WarningReason(
                    "POLICY_AMBIGUITY_INCOMPLETE",
                    "Ambiguity was reported without multiple applicable rules.");
            case "mechanism_not_available" -> new WarningReason("MECHANISM_UNAVAILABLE",
                    "The planned check mechanism was not available.");
            case "survival_coverage_partial" -> new WarningReason(
                    "SURVIVAL_COVERAGE_PARTIAL",
                    "Survival coverage could not be established for all confidentiality "
                            + "provisions.");
            case "decision_contract_error" -> new WarningReason("DECISION_CONTRACT_ERROR",
                    "The analysis decision contract could not be satisfied.");
            default -> environmentReason(text);
        };
    }

    private static WarningReason environmentReason(String text) {
        if (text.startsWith("contract semantics changed")) {
            return new WarningReason("SEMANTICS_CHANGED",
                    "Contract semantics changed after the review snapshot was frozen.");
        }
        if (text.startsWith("retrieval index version changed")) {
            return new WarningReason("INDEX_CHANGED",
                    "The retrieval index version changed after the review snapshot was frozen.");
        }
        if (text.startsWith("embedding model identity changed")) {
            return new WarningReason("EMBEDDING_MODEL_CHANGED",
                    "The embedding model identity changed after the review snapshot was frozen.");
        }
        if (text.startsWith("model policy bindings changed")) {
            return new WarningReason("MODEL_BINDINGS_CHANGED",
                    "The model policy bindings changed after the review snapshot was frozen.");
        }
        return new WarningReason("CHECK_INCOMPLETE",
                "This check could not complete and was recorded as a limitation.");
    }

    private static ReviewSummaryView.FailureView failure(String internalReason) {
        if (internalReason == null || internalReason.isBlank()) {
            return null;
        }
        if (internalReason.startsWith("decision_contract_error")) {
            return new ReviewSummaryView.FailureView("DECISION_CONTRACT_ERROR",
                    "The machine analysis decision contract could not be satisfied.");
        }
        return new ReviewSummaryView.FailureView("ANALYSIS_INTERNAL_ERROR",
                "Machine analysis could not be completed; internal diagnostics were recorded.");
    }

    /**
     * Frozen coverage per declared concept from the counted run's presence executions
     * (DW-024). A secondary-only presence is visible as limited substantive coverage;
     * nothing is inferred when no execution recorded coverage.
     */
    private List<ReviewCoverageView> coverage(
            UUID organizationId, UUID reviewId, Optional<ReviewAnalysisRun> countedRun) {
        var presenceChecks = new ArrayList<ReviewPlanCheck>();
        for (var check : reviewRepository.findPlanChecks(organizationId, reviewId)) {
            if (check.mechanism() == CheckMechanism.PRESENCE) {
                presenceChecks.add(check);
            }
        }
        if (presenceChecks.isEmpty()) {
            return List.of();
        }
        var recorded = new HashMap<UUID, ReviewCoverageView.Coverage>();
        if (countedRun.isPresent()) {
            for (var execution : analysisRepository.findExecutions(
                    organizationId, countedRun.get().id())) {
                var raw = execution.detail().get("coverage");
                if (raw == null) {
                    continue;
                }
                try {
                    recorded.put(execution.planCheckId(),
                            ReviewCoverageView.Coverage.valueOf(raw.toString()));
                } catch (IllegalArgumentException e) {
                    recorded.put(execution.planCheckId(), ReviewCoverageView.Coverage.UNKNOWN);
                }
            }
        }
        var views = new ArrayList<ReviewCoverageView>();
        for (var check : presenceChecks) {
            views.add(new ReviewCoverageView(check.concept(), check.expectation(),
                    recorded.getOrDefault(check.id(), ReviewCoverageView.Coverage.UNKNOWN)));
        }
        return List.copyOf(views);
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
            Finding finding,
            List<RetrievalResponses.EvidenceItemResponse> evidenceItems,
            FindingDetailView.AbsenceBasisView absenceBasis) {
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
                finding.evidenceItemIds(), evidenceItems, finding.signalIds(), absenceBasis,
                finding.createdAt());
    }

    private record Warnings(List<AnalysisWarningView> items, boolean truncated) {
    }
}
