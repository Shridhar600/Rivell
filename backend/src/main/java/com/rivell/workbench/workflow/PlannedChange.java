package com.rivell.workbench.workflow;

import java.util.Objects;
import java.util.UUID;

import com.rivell.workbench.contracts.LegalConcept;
import com.rivell.workbench.redline.ChangeActionType;
import com.rivell.workbench.redline.ChangeLanguageSource;
import com.rivell.workbench.redline.ChangeTargetKind;

/** One resolved decision's executable change before finalization assigns set identity. */
public record PlannedChange(
        UUID findingId,
        UUID decisionId,
        ChangeActionType actionType,
        ChangeTargetKind targetKind,
        UUID targetHeadingNodeId,
        LegalConcept absenceConcept,
        String approvedLanguage,
        ChangeLanguageSource languageSource,
        UUID approvedDraftingEvidenceId) {

    public PlannedChange {
        Objects.requireNonNull(findingId, "findingId");
        Objects.requireNonNull(decisionId, "decisionId");
        Objects.requireNonNull(actionType, "actionType");
        Objects.requireNonNull(targetKind, "targetKind");
        Objects.requireNonNull(languageSource, "languageSource");
        if ((targetKind == ChangeTargetKind.CLAUSE) != (targetHeadingNodeId != null)) {
            throw new IllegalArgumentException("clause targets require a heading node id");
        }
        if ((targetKind == ChangeTargetKind.ABSENCE) != (absenceConcept != null)) {
            throw new IllegalArgumentException("absence targets require a concept");
        }
    }
}
