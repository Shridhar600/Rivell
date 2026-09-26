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
 * and ESCALATE produce no change. No model participates.
 */
public final class DecisionRules {

    public static final int MAX_COMMENT_LENGTH = 2000;
    public static final int MAX_LANGUAGE_LENGTH = 20000;

    private DecisionRules() {
    }

    /** Validates the request shape independently of the finding. */
    public static void validateRequest(
            DecisionAction action, ChangeActionType approvedAction,
            String approvedLanguage, String comment) {
        if (action == null) {
            throw new InvalidDecisionException("action is required");
        }
        switch (action) {
            case ACCEPT -> {
                if (approvedAction != null || approvedLanguage != null) {
                    throw new InvalidDecisionException(
                            "ACCEPT approves the machine recommendation as-is and carries "
                                    + "no action or language");
                }
            }
            case EDIT -> {
                if (approvedLanguage != null) {
                    requireLanguage(approvedLanguage);
                }
            }
            case REJECT, ESCALATE -> {
                if (approvedAction != null || approvedLanguage != null) {
                    throw new InvalidDecisionException(
                            action + " carries no action or language");
                }
                if (comment == null || comment.isBlank()) {
                    throw new InvalidDecisionException(
                            action + " requires a comment explaining the decision");
                }
            }
        }
        if (comment != null && comment.length() > MAX_COMMENT_LENGTH) {
            throw new InvalidDecisionException(
                    "comment exceeds " + MAX_COMMENT_LENGTH + " characters");
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
            case REJECT, ESCALATE -> throw new IllegalArgumentException(
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
                    "a clause finding is edited as REPLACE or DELETE, not INSERT");
        }
        if (targetKind == ChangeTargetKind.ABSENCE
                && actionType != ChangeActionType.INSERT) {
            throw new InvalidDecisionException(
                    "an absence finding is edited as INSERT");
        }
        if (actionType == ChangeActionType.DELETE) {
            if (language != null) {
                throw new InvalidDecisionException("a deletion carries no language");
            }
        } else {
            if (language == null || language.isBlank()) {
                throw new InvalidDecisionException(
                        "the edited language is required for " + actionType);
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
            throw new InvalidDecisionException("approved language must not be blank");
        }
        if (language.length() > MAX_LANGUAGE_LENGTH) {
            throw new InvalidDecisionException(
                    "approved language exceeds " + MAX_LANGUAGE_LENGTH + " characters");
        }
    }
}
