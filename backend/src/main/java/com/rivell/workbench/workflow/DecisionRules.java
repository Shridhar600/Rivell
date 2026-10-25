package com.rivell.workbench.workflow;

import java.util.UUID;

import com.rivell.workbench.redline.ChangeActionType;
import com.rivell.workbench.redline.ChangeLanguageSource;
import com.rivell.workbench.redline.ChangeTargetKind;
import com.rivell.workbench.review.Finding;
import com.rivell.workbench.review.FindingSubjectKind;
import com.rivell.workbench.review.ProposedAction;
import com.rivell.workbench.review.ProposedLanguageSource;

/**
 * Deterministic decision validation and change derivation. ACCEPT approves the machine
 * recommendation as-is (strictly executable); EDIT authors the action/language; REJECT
 * and ESCALATE produce no change; RESOLVE records who approved an escalated clause and
 * produces no change. No model participates.
 */
public final class DecisionRules {

    public static final int MAX_COMMENT_LENGTH = 2000;
    public static final int MAX_LANGUAGE_LENGTH = 20000;
    public static final int MAX_APPROVED_BY_LENGTH = 120;

    private DecisionRules() {
    }

    /** Validates the request shape independently of the finding. */
    public static void validateRequest(
            DecisionAction action, ChangeActionType approvedAction,
            String approvedLanguage, String comment, RejectReason rejectReason,
            String approvedBy) {
        if (action == null) {
            throw new InvalidDecisionException("Choose a decision.");
        }
        switch (action) {
            case ACCEPT -> {
                if (approvedAction != null || approvedLanguage != null) {
                    throw new InvalidDecisionException(
                            "Accept keeps Rivell's suggestion as it is. To change it, choose edit.");
                }
            }
            case EDIT -> {
                if (approvedLanguage != null) {
                    requireLanguage(approvedLanguage);
                }
            }
            case REJECT -> {
                if (approvedAction != null || approvedLanguage != null) {
                    throw new InvalidDecisionException(
                            "A reject decision cannot include new wording.");
                }
                if (rejectReason == null && (comment == null || comment.isBlank())) {
                    throw new InvalidDecisionException(
                            "Add a comment that explains why you chose to reject.");
                }
            }
            case ESCALATE -> {
                if (approvedAction != null || approvedLanguage != null) {
                    throw new InvalidDecisionException(
                            "An escalate decision cannot include new wording.");
                }
                if (comment == null || comment.isBlank()) {
                    throw new InvalidDecisionException(
                            "Add a comment that explains why you chose to escalate.");
                }
            }
            case RESOLVE -> {
                if (approvedAction != null || approvedLanguage != null) {
                    throw new InvalidDecisionException(
                            "Resolving an escalation cannot include new wording.");
                }
                if (approvedBy == null || approvedBy.isBlank()) {
                    throw new InvalidDecisionException(
                            "Add the name of the person who approved this clause.");
                }
            }
        }
        if (rejectReason != null && action != DecisionAction.REJECT) {
            throw new InvalidDecisionException(
                    "A rejection reason belongs only to a reject decision.");
        }
        if (approvedBy != null) {
            if (action != DecisionAction.RESOLVE) {
                throw new InvalidDecisionException(
                        "Only a resolved escalation carries the approver's name.");
            }
            var trimmed = approvedBy.strip();
            if (trimmed.isEmpty()) {
                throw new InvalidDecisionException(
                        "Add the name of the person who approved this clause.");
            }
            if (trimmed.length() > MAX_APPROVED_BY_LENGTH) {
                throw new InvalidDecisionException(
                        "The approver's name is too long. Keep it to "
                                + MAX_APPROVED_BY_LENGTH + " characters or fewer.");
            }
        }
        if (comment != null && comment.length() > MAX_COMMENT_LENGTH) {
            throw new InvalidDecisionException(
                    "The comment is too long. Keep it to " + MAX_COMMENT_LENGTH
                            + " characters or fewer.");
        }
    }

    /**
     * Derives the executable change for an ACCEPT or EDIT decision. ACCEPT is invalid
     * for recommendations with no usable proposal; EDIT requires a language for
     * REPLACE/INSERT and forbids one for DELETE.
     */
    public static PlannedChange plan(
            Finding finding, DecisionAction action, ChangeActionType requestedAction,
            String requestedLanguage, UUID decisionId) {
        return switch (action) {
            case ACCEPT -> acceptPlan(finding, decisionId);
            case EDIT -> editPlan(finding, requestedAction, requestedLanguage, decisionId);
            case REJECT, ESCALATE, RESOLVE -> throw new IllegalArgumentException(
                    "no change is derived for " + action);
        };
    }

    private static PlannedChange acceptPlan(Finding finding, UUID decisionId) {
        var language = finding.proposedLanguage();
        var source = finding.proposedLanguageSource();
        if (language == null || source == ProposedLanguageSource.NONE) {
            throw new FindingNotActionableException(
                    finding.id(), finding.proposedAction().name());
        }
        if (finding.proposedAction() != ProposedAction.USE_APPROVED_FALLBACK
                && finding.proposedAction() != ProposedAction.CONSIDER_GENERATED_LANGUAGE) {
            throw new FindingNotActionableException(
                    finding.id(), finding.proposedAction().name());
        }
        requireLanguage(language);
        var targetKind = targetKind(finding);
        return new PlannedChange(finding.id(), decisionId,
                targetKind == ChangeTargetKind.ABSENCE
                        ? ChangeActionType.INSERT : ChangeActionType.REPLACE,
                targetKind, finding.subjectHeadingNodeId(), finding.absenceConcept(),
                language, source == ProposedLanguageSource.APPROVED_DRAFTING
                        ? ChangeLanguageSource.APPROVED_DRAFTING
                        : ChangeLanguageSource.GENERATIVE,
                finding.approvedDraftingEvidenceId());
    }

    private static PlannedChange editPlan(
            Finding finding, ChangeActionType requestedAction, String language,
            UUID decisionId) {
        var targetKind = targetKind(finding);
        var actionType = requestedAction != null ? requestedAction
                : targetKind == ChangeTargetKind.CLAUSE
                        ? ChangeActionType.REPLACE : ChangeActionType.INSERT;
        if (targetKind == ChangeTargetKind.CLAUSE
                && actionType == ChangeActionType.INSERT) {
            throw new InvalidDecisionException(
                    "A finding about an existing clause is edited as a replacement or a "
                            + "deletion, not an insertion.");
        }
        if (targetKind == ChangeTargetKind.ABSENCE
                && actionType != ChangeActionType.INSERT) {
            throw new InvalidDecisionException(
                    "A finding about a missing clause can only be edited as an insertion.");
        }
        if (actionType == ChangeActionType.DELETE) {
            if (language != null) {
                throw new InvalidDecisionException("A deletion cannot include new wording.");
            }
        } else {
            if (language == null || language.isBlank()) {
                throw new InvalidDecisionException(
                        "Add the new wording for this change.");
            }
            requireLanguage(language);
        }
        return new PlannedChange(finding.id(), decisionId, actionType, targetKind,
                finding.subjectHeadingNodeId(), finding.absenceConcept(),
                actionType == ChangeActionType.DELETE ? null : language,
                actionType == ChangeActionType.DELETE
                        ? ChangeLanguageSource.NONE : ChangeLanguageSource.HUMAN_EDIT,
                null);
    }

    /** Maps a stored decision back to its executable change at readiness/finalization. */
    public static PlannedChange planFromDecision(Finding finding, HumanDecision decision) {
        if (!decision.resolved()) {
            throw new IllegalArgumentException("escalations produce no change");
        }
        var targetKind = targetKind(finding);
        var source = decision.languageSource() == null
                ? ChangeLanguageSource.NONE : decision.languageSource();
        return new PlannedChange(finding.id(), decision.id(), decision.approvedAction(),
                targetKind, finding.subjectHeadingNodeId(), finding.absenceConcept(),
                decision.approvedLanguage(), source, decision.approvedDraftingEvidenceId());
    }

    private static ChangeTargetKind targetKind(Finding finding) {
        return finding.subjectKind() == FindingSubjectKind.ABSENCE
                ? ChangeTargetKind.ABSENCE : ChangeTargetKind.CLAUSE;
    }

    private static void requireLanguage(String language) {
        if (language.isBlank()) {
            throw new InvalidDecisionException("The new wording cannot be blank.");
        }
        if (language.length() > MAX_LANGUAGE_LENGTH) {
            throw new InvalidDecisionException(
                    "The new wording is too long. Keep it to " + MAX_LANGUAGE_LENGTH
                            + " characters or fewer.");
        }
    }
}
