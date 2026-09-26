package com.rivell.workbench.workflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.rivell.workbench.redline.ChangeActionType;
import com.rivell.workbench.redline.ChangeTargetKind;
import com.rivell.workbench.review.Finding;
import com.rivell.workbench.review.FindingClassification;

/**
 * Pure readiness resolution over one run's findings and their current decisions: counts,
 * status, typed finalization blockers, and the executable changes a finalization would
 * freeze. Target conflicts are target-level (no diff engine); code-owned messages.
 */
public final class HumanReviewResolver {

    private static final int MAX_BLOCKER_FINDINGS = 20;

    private HumanReviewResolver() {
    }

    public static HumanReviewAssessment resolve(
            List<Finding> findings,
            Map<UUID, HumanDecision> currentDecisions,
            boolean analysisComplete,
            boolean analysisProducedNoTrustworthyResult,
            boolean finalized,
            Set<UUID> headingNodeIds) {
        var planned = new ArrayList<PlannedChange>();
        var unresolvedBlocking = new ArrayList<UUID>();
        var unresolvedReviewRequired = new ArrayList<UUID>();
        var escalated = new ArrayList<UUID>();
        var missingLanguage = new ArrayList<UUID>();
        var invalidTarget = new ArrayList<UUID>();
        var accepted = 0;
        var edited = 0;
        var rejected = 0;
        var escalatedCount = 0;
        var actionable = 0;
        var resolved = 0;
        var informational = 0;
        var informationalDecided = 0;
        var decisionsPresent = 0;

        for (var finding : findings) {
            var decision = currentDecisions.get(finding.id());
            var isActionable = finding.reviewClassification() != FindingClassification.INFORMATIONAL;
            if (isActionable) {
                actionable++;
            } else {
                informational++;
            }
            if (decision == null) {
                if (isActionable) {
                    if (finding.reviewClassification() == FindingClassification.BLOCKING) {
                        unresolvedBlocking.add(finding.id());
                    } else {
                        unresolvedReviewRequired.add(finding.id());
                    }
                }
                continue;
            }
            decisionsPresent++;
            if (!isActionable) {
                informationalDecided++;
            }
            switch (decision.action()) {
                case ACCEPT -> accepted++;
                case EDIT -> edited++;
                case REJECT -> rejected++;
                case ESCALATE -> escalatedCount++;
            }
            if (decision.resolved()) {
                if (isActionable) {
                    resolved++;
                }
                if (decision.approvedAction() == null) {
                    continue;
                }
                var change = DecisionRules.planFromDecision(finding, decision);
                if (change.actionType() != ChangeActionType.DELETE
                        && (change.approvedLanguage() == null
                                || change.approvedLanguage().isBlank())) {
                    missingLanguage.add(finding.id());
                } else if (change.targetKind() == ChangeTargetKind.CLAUSE
                        && !headingNodeIds.contains(change.targetHeadingNodeId())) {
                    invalidTarget.add(finding.id());
                } else {
                    planned.add(change);
                }
            } else {
                escalated.add(finding.id());
            }
        }

        var conflicts = conflictingFindings(planned);

        var blockers = new ArrayList<FinalizationBlocker>();
        if (!analysisComplete) {
            blockers.add(blocker(FinalizationBlockerCode.ANALYSIS_NOT_COMPLETE,
                    "Machine analysis is not complete; decisions and finalization are "
                            + "unavailable until the review completes.", List.of()));
        }
        if (analysisProducedNoTrustworthyResult) {
            // No planned check completed. The run still reports COMPLETED, so finalizing here would
            // turn an analysis that never really ran into a clean-looking zero-change artifact.
            // Block until the environment matches the review snapshot and the review is re-analyzed.
            blockers.add(blocker(FinalizationBlockerCode.ANALYSIS_DEGRADED,
                    "No planned check completed a trustworthy analysis, so the review has no "
                            + "machine findings to decide on. It cannot be finalized until the "
                            + "analysis environment matches the review snapshot and the review "
                            + "is re-analyzed.", List.of()));
        }
        if (!unresolvedBlocking.isEmpty()) {
            blockers.add(blocker(FinalizationBlockerCode.UNRESOLVED_BLOCKING_FINDINGS,
                    unresolvedBlocking.size()
                            + " blocking finding(s) have no resolved decision.",
                    unresolvedBlocking));
        }
        if (!unresolvedReviewRequired.isEmpty()) {
            blockers.add(blocker(FinalizationBlockerCode.UNRESOLVED_REVIEW_REQUIRED_FINDINGS,
                    unresolvedReviewRequired.size()
                            + " review-required finding(s) have no resolved decision.",
                    unresolvedReviewRequired));
        }
        if (!escalated.isEmpty()) {
            blockers.add(blocker(FinalizationBlockerCode.ACTIVE_ESCALATION,
                    escalated.size() + " escalated finding(s) remain unresolved.",
                    escalated));
        }
        if (!conflicts.isEmpty()) {
            blockers.add(blocker(FinalizationBlockerCode.APPROVED_CHANGE_CONFLICT,
                    conflicts.size() + " approved change(s) target the same clause or "
                            + "provision and cannot be combined.", conflicts));
        }
        if (!missingLanguage.isEmpty()) {
            blockers.add(blocker(FinalizationBlockerCode.MISSING_APPROVED_LANGUAGE,
                    missingLanguage.size() + " resolved decision(s) lack approved language.",
                    missingLanguage));
        }
        if (!invalidTarget.isEmpty()) {
            blockers.add(blocker(FinalizationBlockerCode.INVALID_SOURCE_TARGET,
                    invalidTarget.size() + " approved change(s) target a clause that is not "
                            + "part of the frozen contract structure.", invalidTarget));
        }

        var ready = analysisComplete && blockers.isEmpty() && !finalized;
        var status = status(analysisComplete, finalized, decisionsPresent, actionable,
                blockers.isEmpty());
        var progress = new HumanReviewProgress(status, actionable, resolved,
                actionable - resolved, accepted, edited, rejected, escalatedCount,
                informational, informationalDecided, ready, blockers);
        return new HumanReviewAssessment(progress, planned);
    }

    private static HumanReviewStatus status(
            boolean analysisComplete, boolean finalized, int decisionsPresent,
            int actionable, boolean noBlockers) {
        if (finalized) {
            return HumanReviewStatus.COMPLETED;
        }
        if (!analysisComplete || (decisionsPresent == 0 && actionable > 0)) {
            return HumanReviewStatus.NOT_STARTED;
        }
        return noBlockers ? HumanReviewStatus.READY_FOR_COMPLETION : HumanReviewStatus.IN_PROGRESS;
    }

    private static List<UUID> conflictingFindings(List<PlannedChange> planned) {
        var byTarget = new LinkedHashMap<String, List<UUID>>();
        for (var change : planned) {
            var key = change.targetKind() == ChangeTargetKind.CLAUSE
                    ? "CLAUSE:" + change.targetHeadingNodeId()
                    : "ABSENCE:" + change.absenceConcept();
            byTarget.computeIfAbsent(key, ignored -> new ArrayList<>()).add(change.findingId());
        }
        var conflicts = new LinkedHashSet<UUID>();
        for (var entry : byTarget.entrySet()) {
            if (entry.getValue().size() > 1) {
                conflicts.addAll(entry.getValue());
            }
        }
        return List.copyOf(conflicts);
    }

    private static FinalizationBlocker blocker(
            FinalizationBlockerCode code, String message, List<UUID> findingIds) {
        var bounded = findingIds.size() > MAX_BLOCKER_FINDINGS
                ? List.copyOf(findingIds.subList(0, MAX_BLOCKER_FINDINGS))
                : List.copyOf(findingIds);
        return new FinalizationBlocker(code, message, bounded);
    }
}
