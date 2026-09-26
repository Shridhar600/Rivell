package com.rivell.workbench.workflow.api;

import java.util.UUID;

import com.rivell.workbench.contracts.LegalConcept;

/** One frozen approved change: action, canonical target, exact language, provenance. */
public record ApprovedChangeView(
        UUID id,
        UUID findingId,
        UUID decisionId,
        int ordinal,
        String actionType,
        String targetKind,
        UUID targetHeadingNodeId,
        LegalConcept absenceConcept,
        String approvedLanguage,
        String languageSource,
        UUID approvedDraftingEvidenceId) {
}
