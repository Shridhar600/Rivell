package com.rivell.workbench.workflow.api;

import java.time.Instant;
import java.util.UUID;

/** One human decision revision (the current revision for the decision read). */
public record DecisionView(
        UUID id,
        UUID findingId,
        int revision,
        String action,
        String approvedAction,
        String approvedLanguage,
        String languageSource,
        UUID approvedDraftingEvidenceId,
        String comment,
        UUID actorId,
        Instant decidedAt,
        UUID clientRequestId,
        UUID supersedesDecisionId) {
}
