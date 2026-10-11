package com.rivell.workbench.workflow.api;

import java.util.UUID;



/** One frozen approved change: action, canonical target, exact language, provenance. */
public record ApprovedChangeView(
        UUID id,
        UUID findingId,
        UUID decisionId,
        int ordinal,
        String actionType,
        String targetKind,
        UUID targetHeadingNodeId,
        String absenceConcept,
        String approvedLanguage,
        String languageSource,
        UUID approvedDraftingEvidenceId) {
}
