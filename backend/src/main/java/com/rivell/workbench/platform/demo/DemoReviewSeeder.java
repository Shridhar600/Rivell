package com.rivell.workbench.platform.demo;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rivell.workbench.contracts.ContractEnrichmentService;
import com.rivell.workbench.contracts.ContractSemantics;
import com.rivell.workbench.contracts.FactCorrection;
import com.rivell.workbench.contracts.LegalConcept;
import com.rivell.workbench.contracts.ClauseSemantics;
import com.rivell.workbench.redline.ApprovedChangeSet;
import com.rivell.workbench.redline.ApprovedChangeSetRepository;
import com.rivell.workbench.redline.ChangeActionType;
import com.rivell.workbench.redline.RedlineExecution;
import com.rivell.workbench.redline.RedlineExecutionFailedException;
import com.rivell.workbench.redline.RedlineExecutionNotFoundException;
import com.rivell.workbench.redline.RedlineExecutionService;
import com.rivell.workbench.redline.RedlineExecutionStatus;
import com.rivell.workbench.redline.RedlineFailureCode;
import com.rivell.workbench.review.Finding;
import com.rivell.workbench.review.FindingClassification;
import com.rivell.workbench.review.FindingRepository;
import com.rivell.workbench.review.FindingSubjectKind;
import com.rivell.workbench.review.ProposedAction;
import com.rivell.workbench.review.Review;
import com.rivell.workbench.review.ReviewAnalysisRepository;
import com.rivell.workbench.review.ReviewAnalysisService;
import com.rivell.workbench.review.ReviewPackRegistry;
import com.rivell.workbench.review.ReviewRepository;
import com.rivell.workbench.review.ReviewService;
import com.rivell.workbench.review.ReviewStatus;
import com.rivell.workbench.workflow.DecisionAction;
import com.rivell.workbench.workflow.DecisionCommand;
import com.rivell.workbench.workflow.FinalizationBlockerCode;
import com.rivell.workbench.workflow.HumanDecision;
import com.rivell.workbench.workflow.HumanDecisionRepository;
import com.rivell.workbench.workflow.HumanDecisionService;
import com.rivell.workbench.workflow.HumanReviewProgress;
import com.rivell.workbench.workflow.HumanReviewService;
import com.rivell.workbench.workflow.ReviewFinalizationService;

/**
 * Drives the review scenarios through the real services. Every decision, finalization,
 * redline execution and failure is produced by the domain: nothing writes a status, a
 * Finding, a decision or a change set directly. Reviews are created with a deterministic
 * client request key so a rerun replays the existing review instead of freezing a second
 * snapshot.
 */
final class DemoReviewSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoReviewSeeder.class);
    private static final String REJECTION_NOTE =
            "Resolved for the local demo scenario; no further action required.";
    private static final String ESCALATION_NOTE =
            "Escalated for the local demo scenario: the counterparty position needs general "
                    + "counsel review before it can be accepted.";
    private static final String INSERT_LANGUAGE =
            "Total aggregate liability shall not exceed the fees paid in the preceding 12 months.";

    private final ReviewService reviewService;
    private final ReviewAnalysisService analysis;
    private final ContractEnrichmentService enrichment;
    private final HumanDecisionService decisions;
    private final HumanDecisionRepository decisionRepository;
    private final HumanReviewService humanReview;
    private final ReviewFinalizationService finalization;
    private final ApprovedChangeSetRepository changeSets;
    private final RedlineExecutionService redline;
    private final ReviewAnalysisRepository analysisRepository;
    private final FindingRepository findingRepository;
    private final ReviewRepository reviewRepository;
    private final DemoAnalysisFaultPolicy faultPolicy;

    DemoReviewSeeder(ReviewService reviewService, ReviewAnalysisService analysis,
            ContractEnrichmentService enrichment, HumanDecisionService decisions,
            HumanDecisionRepository decisionRepository, HumanReviewService humanReview,
            ReviewFinalizationService finalization, ApprovedChangeSetRepository changeSets,
            RedlineExecutionService redline, ReviewAnalysisRepository analysisRepository,
            FindingRepository findingRepository, ReviewRepository reviewRepository,
            DemoAnalysisFaultPolicy faultPolicy) {
        this.reviewService = reviewService;
        this.analysis = analysis;
        this.enrichment = enrichment;
        this.decisions = decisions;
        this.decisionRepository = decisionRepository;
        this.humanReview = humanReview;
        this.finalization = finalization;
        this.changeSets = changeSets;
        this.redline = redline;
        this.analysisRepository = analysisRepository;
        this.findingRepository = findingRepository;
        this.reviewRepository = reviewRepository;
        this.faultPolicy = faultPolicy;
    }

    record ReviewSeedResult(int reviews, int findings) {
    }

    ReviewSeedResult seed(Map<String, DemoContractSeeder.SeededContract> contracts) {
        var reviews = new ArrayList<Review>();
        reviews.add(readyForReview(contracts.get(DemoDocuments.VENDOR_NDA)));
        reviews.add(inReview(contracts.get(DemoDocuments.ACME_MSA)));
        reviews.add(escalationBlocking(contracts.get(DemoDocuments.HOOLI_MSA)));
        reviews.add(readyToFinalize(contracts.get(DemoDocuments.PROCUREMENT_NDA)));
        reviews.add(finalizedWithChanges(contracts.get(DemoDocuments.ATLAS_MSA)));
        reviews.add(finalizedNoChanges(contracts.get(DemoDocuments.EMPLOYEE_NDA)));
        reviews.add(verifiedRedline(contracts.get(DemoDocuments.PROCUREMENT_MSA)));
        reviews.add(redlineFailed(contracts.get(DemoDocuments.GLOBEX_NDA)));
        reviews.add(retrySucceeded(contracts.get(DemoDocuments.INITECH_NDA)));
        reviews.add(analysisFailed(contracts.get(DemoDocuments.UMBRELLA_MSA)));
        reviews.add(limitedAnalysis(contracts.get(DemoDocuments.SOYLENT_MSA)));
        reviews.add(queued(contracts.get(DemoDocuments.VANDELAY_MSA)));

        var findings = 0;
        for (var review : reviews) {
            if (analysisRepository.findLatestCompletedRun(review.organizationId(), review.id())
                    .isPresent()) {
                findings += findings(review).size();
            }
        }
        return new ReviewSeedResult(reviews.size(), findings);
    }

    private Review readyForReview(DemoContractSeeder.SeededContract contract) {
        var review = ensureReview(contract, DemoScenarioCatalog.R01_READY);
        analyze(review);
        require(findings(review).size() >= 2,
                "R01 needs multiple real Findings for a workspace walkthrough");
        return review;
    }

    private Review inReview(DemoContractSeeder.SeededContract contract) {
        var review = ensureReview(contract, DemoScenarioCatalog.R02_IN_REVIEW);
        analyze(review);
        var produced = findings(review);
        decide(review, actionableFallback(produced), DecisionAction.ACCEPT, null, null, null);
        var progress = progress(review);
        require(!progress.readyToFinalize() && progress.remainingFindings() > 0,
                "R02 must remain in progress with unresolved findings");
        return review;
    }

    private Review escalationBlocking(DemoContractSeeder.SeededContract contract) {
        var review = ensureReview(contract, DemoScenarioCatalog.R03_ESCALATION);
        analyze(review);
        var target = findings(review).stream()
                .filter(finding -> finding.reviewClassification()
                        != FindingClassification.INFORMATIONAL)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("R03 requires an actionable finding"));
        decide(review, target, DecisionAction.ESCALATE, null, null, ESCALATION_NOTE);
        var progress = progress(review);
        require(!progress.readyToFinalize(), "R03 must not be ready to finalize");
        require(progress.blockers().stream()
                        .anyMatch(blocker -> blocker.code() == FinalizationBlockerCode.ACTIVE_ESCALATION),
                "R03 must report the ACTIVE_ESCALATION blocker");
        return review;
    }

    private Review readyToFinalize(DemoContractSeeder.SeededContract contract) {
        var review = ensureReview(contract, DemoScenarioCatalog.R04_READY_TO_FINALIZE);
        analyze(review);
        for (var finding : requiredFindings(findings(review))) {
            decide(review, finding, DecisionAction.REJECT, null, null, REJECTION_NOTE);
        }
        require(progress(review).readyToFinalize(),
                "R04 must be ready to finalize after every required finding is resolved");
        require(changeSet(review).isEmpty(), "R04 must not be finalized");
        return review;
    }

    private Review finalizedWithChanges(DemoContractSeeder.SeededContract contract) {
        var review = ensureReview(contract, DemoScenarioCatalog.R05_FINALIZED_CHANGES);
        analyze(review);
        var produced = findings(review);
        var fallback = actionableFallback(produced);
        decide(review, fallback, DecisionAction.ACCEPT, null, null, null);
        for (var finding : requiredFindings(produced)) {
            if (!finding.id().equals(fallback.id())) {
                decide(review, finding, DecisionAction.REJECT, null, null, REJECTION_NOTE);
            }
        }
        finalizeIfNeeded(review);
        var frozen = changeSet(review).orElseThrow();
        require(!frozen.changes().isEmpty(), "R05 must freeze at least one approved change");
        require(currentExecution(review).isEmpty(), "R05 must not generate a redline");
        return review;
    }

    private Review finalizedNoChanges(DemoContractSeeder.SeededContract contract) {
        var review = ensureReview(contract, DemoScenarioCatalog.R06_FINALIZED_NO_CHANGES);
        analyze(review);
        for (var finding : requiredFindings(findings(review))) {
            decide(review, finding, DecisionAction.REJECT, null, null, REJECTION_NOTE);
        }
        finalizeIfNeeded(review);
        var frozen = changeSet(review).orElseThrow();
        require(frozen.changes().isEmpty(),
                "R06 must finalize with an empty change set, not " + frozen.changes().size());
        return review;
    }

    private Review verifiedRedline(DemoContractSeeder.SeededContract contract) {
        var review = ensureReview(contract, DemoScenarioCatalog.R07_VERIFIED_REDLINE);
        analyze(review);
        var produced = findings(review);
        var fallback = actionableFallback(produced);
        decide(review, fallback, DecisionAction.ACCEPT, null, null, null);
        for (var finding : requiredFindings(produced)) {
            if (!finding.id().equals(fallback.id())) {
                decide(review, finding, DecisionAction.REJECT, null, null, REJECTION_NOTE);
            }
        }
        finalizeIfNeeded(review);
        if (currentExecution(review).isEmpty()) {
            redline.execute(review.id());
        }
        var execution = currentExecution(review).orElseThrow();
        require(execution.status() == RedlineExecutionStatus.COMPLETED
                        && execution.downloadable(),
                "R07 must produce a verified downloadable redline");
        return review;
    }

    private Review redlineFailed(DemoContractSeeder.SeededContract contract) {
        var review = ensureReview(contract, DemoScenarioCatalog.R08_REDLINE_FAILED);
        analyze(review);
        shapeInsertChangeSet(review);
        if (currentExecution(review).isEmpty()) {
            applySemanticsCorrection(review);
            expectRedlineFailure(review);
        }
        var execution = currentExecution(review).orElseThrow();
        require(execution.status() == RedlineExecutionStatus.FAILED
                        && execution.failureCode() == RedlineFailureCode.APPROVED_SEMANTICS_DRIFT,
                "R08 must fail closed with APPROVED_SEMANTICS_DRIFT");
        require(!execution.downloadable(), "R08 must not expose an artifact");
        return review;
    }

    private Review retrySucceeded(DemoContractSeeder.SeededContract contract) {
        var review = ensureReview(contract, DemoScenarioCatalog.R09_RETRY_SUCCEEDED);
        analyze(review);
        shapeInsertChangeSet(review);
        var latest = currentExecution(review);
        if (latest.isEmpty()) {
            applySemanticsCorrection(review);
            expectRedlineFailure(review);
            latest = currentExecution(review);
        }
        if (latest.orElseThrow().status() == RedlineExecutionStatus.FAILED) {
            restoreSemantics(review);
            redline.retry(review.id());
        }
        var execution = currentExecution(review).orElseThrow();
        require(execution.attemptNumber() == 2
                        && execution.status() == RedlineExecutionStatus.COMPLETED
                        && execution.downloadable(),
                "R09 must complete on attempt 2 with the prior failure preserved");
        return review;
    }

    private Review analysisFailed(DemoContractSeeder.SeededContract contract) {
        var review = ensureReview(contract, DemoScenarioCatalog.R10_ANALYSIS_FAILED);
        var current = reviewRepository.find(review.organizationId(), review.id()).orElseThrow();
        if (current.status() != ReviewStatus.FAILED) {
            faultPolicy.failFor(review.id());
            try {
                analysis.analyze(review.organizationId(), review.id());
            } catch (RuntimeException expected) {
                log.info("demo scenario: R10 analysis failed as injected: {}",
                        expected.getClass().getSimpleName());
            } finally {
                faultPolicy.clear();
            }
        }
        var failed = reviewRepository.find(review.organizationId(), review.id()).orElseThrow();
        require(failed.status() == ReviewStatus.FAILED,
                "R10 must reach a genuine FAILED review, not " + failed.status());
        require(failed.failureReason() != null && !failed.failureReason().isBlank(),
                "R10 must persist a failure reason");
        return failed;
    }

    private Review limitedAnalysis(DemoContractSeeder.SeededContract contract) {
        var review = ensureReview(contract, DemoScenarioCatalog.R11_LIMITED_ANALYSIS);
        if (review.status() != ReviewStatus.COMPLETED) {
            applySemanticsCorrection(review);
            analyze(review);
        }
        var runId = completedRun(review);
        var executions = analysisRepository.findExecutions(review.organizationId(), runId);
        require(!executions.isEmpty() && executions.stream().allMatch(execution ->
                        execution.status() == com.rivell.workbench.review.CheckExecutionStatus.DEGRADED),
                "R11 must degrade every check through the real environment mismatch");
        var progress = progress(review);
        require(!progress.readyToFinalize() && progress.blockers().stream()
                        .anyMatch(blocker -> blocker.code() == FinalizationBlockerCode.ANALYSIS_DEGRADED),
                "R11 must report ANALYSIS_DEGRADED and block finalization");
        return review;
    }

    private Review queued(DemoContractSeeder.SeededContract contract) {
        var review = ensureReview(contract, DemoScenarioCatalog.R12_QUEUED);
        require(review.status() == ReviewStatus.ANALYSIS_QUEUED,
                "R12 must stay ANALYSIS_QUEUED while the worker is off");
        return review;
    }

    private Review ensureReview(DemoContractSeeder.SeededContract contract, String key) {
        return reviewService.create(contract.contractId(), 1, contract.packKey(),
                clientRequestId(key));
    }

    private void analyze(Review review) {
        analysis.analyze(review.organizationId(), review.id());
    }

    private void shapeInsertChangeSet(Review review) {
        var produced = findings(review);
        var absence = blockingAbsence(produced);
        decide(review, absence, DecisionAction.EDIT, null, INSERT_LANGUAGE, null);
        finalizeIfNeeded(review);
        var frozen = changeSet(review).orElseThrow();
        require(frozen.changes().stream().anyMatch(change ->
                        change.actionType() == ChangeActionType.INSERT),
                "the scenario requires an INSERT change for the drift guard to inspect");
    }

    private void decide(Review review, Finding finding, DecisionAction action,
            ChangeActionType approvedAction, String language, String comment) {
        var current = decisionRepository.findCurrent(
                review.organizationId(), review.id(), finding.id());
        if (current.isPresent() && current.get().action() == action) {
            return;
        }
        if (current.isPresent() && current.get().resolved()) {
            return;
        }
        var revision = current.map(HumanDecision::revision).orElse(0);
        decisions.decide(review.id(), finding.id(),
                new DecisionCommand(revision, null, action, approvedAction, language, comment));
    }

    private void finalizeIfNeeded(Review review) {
        if (changeSet(review).isEmpty()) {
            finalization.finalizeReview(review.id());
        }
    }

    private Optional<ApprovedChangeSet> changeSet(Review review) {
        return changeSets.findByReview(review.organizationId(), review.id());
    }

    private HumanReviewProgress progress(Review review) {
        return humanReview.state(review.organizationId(), review.id()).assessment().progress();
    }

    private UUID completedRun(Review review) {
        return analysisRepository.findLatestCompletedRun(review.organizationId(), review.id())
                .orElseThrow(() -> new IllegalStateException(
                        "No completed analysis run for " + review.id()))
                .id();
    }

    private List<Finding> findings(Review review) {
        return findingRepository.findFindings(review.organizationId(), completedRun(review));
    }

    private static List<Finding> requiredFindings(List<Finding> produced) {
        return produced.stream()
                .filter(finding -> finding.reviewClassification()
                        != FindingClassification.INFORMATIONAL)
                .toList();
    }

    private static Finding actionableFallback(List<Finding> produced) {
        return produced.stream()
                .filter(finding -> finding.proposedAction() == ProposedAction.USE_APPROVED_FALLBACK
                        && finding.proposedLanguage() != null
                        && !finding.proposedLanguage().isBlank())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "The scenario requires an approved-fallback finding"));
    }

    private static Finding blockingAbsence(List<Finding> produced) {
        return produced.stream()
                .filter(finding -> finding.subjectKind() == FindingSubjectKind.ABSENCE)
                .filter(finding -> finding.reviewClassification() == FindingClassification.BLOCKING)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "The scenario requires a blocking absence finding"));
    }

    private Optional<RedlineExecution> currentExecution(Review review) {
        try {
            return Optional.of(redline.find(review.id()));
        } catch (RedlineExecutionNotFoundException notFound) {
            return Optional.empty();
        }
    }

    private void expectRedlineFailure(Review review) {
        try {
            redline.execute(review.id());
            throw new IllegalStateException("Expected the redline execution to fail closed");
        } catch (RedlineExecutionFailedException expected) {
            log.info("demo scenario: redline failed as designed for review {}", review.id());
        }
    }

    private ContractSemantics semantics(Review review) {
        return enrichment.getSemantics(review.contractId(), review.contractVersionNumber());
    }

    /** A real human semantics correction; drives both the degraded and drift scenarios. */
    private void applySemanticsCorrection(Review review) {
        if (driftedClause(review).isPresent()) {
            return;
        }
        var target = semantics(review).clauses().stream()
                .filter(clause -> clause.primaryConcept().isPresent())
                .filter(clause -> clause.primaryConcept().get() != LegalConcept.GOVERNING_LAW)
                .filter(clause -> !clause.conceptCorrected())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No classified clause to correct for review " + review.id()));
        enrichment.correctClause(review.contractId(), review.contractVersionNumber(),
                target.id(), new FactCorrection("GOVERNING_LAW", Map.of(), UUID.randomUUID()));
    }

    private void restoreSemantics(Review review) {
        var drifted = driftedClause(review).orElseThrow();
        var machine = drifted.primaryConcept().orElseThrow();
        enrichment.correctClause(review.contractId(), review.contractVersionNumber(),
                drifted.id(), new FactCorrection(machine.name(), Map.of(), UUID.randomUUID()));
    }

    private Optional<ClauseSemantics> driftedClause(Review review) {
        return semantics(review).clauses().stream()
                .filter(ClauseSemantics::conceptCorrected)
                .filter(clause -> clause.effectivePrimaryConcept().isPresent()
                        && clause.effectivePrimaryConcept().get() == LegalConcept.GOVERNING_LAW)
                .filter(clause -> clause.primaryConcept().isPresent()
                        && clause.primaryConcept().get() != LegalConcept.GOVERNING_LAW)
                .findFirst();
    }

    private static UUID clientRequestId(String key) {
        return UUID.nameUUIDFromBytes(
                ("rivell-demo/B5/" + key).getBytes(StandardCharsets.UTF_8));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("Demo scenario invariant failed: " + message);
        }
    }
}
