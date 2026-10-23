package com.rivell.workbench.workflow;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.rivell.workbench.redline.ChangeActionType;
import com.rivell.workbench.redline.ChangeLanguageSource;

/**
 * One immutable human-authority revision applied to one machine Finding. Revisions are
 * append-only; the current effective decision is the highest revision. M7 Finding state
 * is never mutated by a decision.
 */
public record HumanDecision(
        UUID id,
        UUID organizationId,
        UUID reviewId,
        UUID findingId,
        UUID runId,
        int revision,
        DecisionAction action,
        ChangeActionType approvedAction,
        String approvedLanguage,
        ChangeLanguageSource languageSource,
        UUID approvedDraftingEvidenceId,
        String comment,
        RejectReason rejectReason,
        String approvedBy,
        UUID actorId,
        Instant decidedAt,
        UUID clientRequestId,
        UUID supersedesDecisionId) {

    public HumanDecision {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(reviewId, "reviewId");
        Objects.requireNonNull(findingId, "findingId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(decidedAt, "decidedAt");
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be >= 1");
        }
        var executable = action == DecisionAction.ACCEPT || action == DecisionAction.EDIT;
        if (executable != (approvedAction != null)) {
            throw new IllegalArgumentException(
                    "approved action is set iff the decision is ACCEPT or EDIT");
        }
        if (!executable
                && (approvedLanguage != null || languageSource != null
                        || approvedDraftingEvidenceId != null)) {
            throw new IllegalArgumentException(
                    "rejections and escalations carry no approved language");
        }
        if (approvedAction == ChangeActionType.DELETE
                && (approvedLanguage != null || languageSource != null)) {
            throw new IllegalArgumentException("deletions carry no approved language");
        }
        if (approvedAction != null && approvedAction != ChangeActionType.DELETE
                && (approvedLanguage == null || languageSource == null)) {
            throw new IllegalArgumentException(
                    "replacements and insertions require approved language and its source");
        }
        if ((languageSource == ChangeLanguageSource.APPROVED_DRAFTING)
                != (approvedDraftingEvidenceId != null)) {
            throw new IllegalArgumentException(
                    "drafting evidence id is set iff the source is approved drafting");
        }
        if (!executable && action != DecisionAction.RESOLVE
                && (comment == null || comment.isBlank())
                && !(action == DecisionAction.REJECT && rejectReason != null)) {
            throw new IllegalArgumentException(
                    "only a rejection without a reason or an escalation requires a comment");
        }
        if (rejectReason != null && action != DecisionAction.REJECT) {
            throw new IllegalArgumentException("only a rejection carries a rejection reason");
        }
        if ((approvedBy != null) != (action == DecisionAction.RESOLVE)) {
            throw new IllegalArgumentException("an approver name is set iff the decision resolves");
        }
        if (approvedBy != null && (approvedBy.isBlank() || approvedBy.length() > 120)) {
            throw new IllegalArgumentException("an approver name must be 1 to 120 characters");
        }
    }

    public boolean resolved() {
        return action != DecisionAction.ESCALATE;
    }
}
