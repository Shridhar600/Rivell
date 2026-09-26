package com.rivell.workbench.redline;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One ApprovedChange resolved to concrete document anchors. {@code deletionTargets} are
 * paragraphs to tracked-delete (REPLACE body / DELETE heading+body); {@code insertion}
 * is the tracked insertion (REPLACE/INSERT) or null for DELETE.
 */
public record PlannedDocumentOperation(
        UUID approvedChangeId,
        int ordinal,
        ChangeActionType actionType,
        List<PlannedParagraph> deletionTargets,
        PlannedInsertion insertion,
        String placementLabel,
        String targetLabel) {

    public PlannedDocumentOperation {
        Objects.requireNonNull(approvedChangeId, "approvedChangeId");
        Objects.requireNonNull(actionType, "actionType");
        Objects.requireNonNull(targetLabel, "targetLabel");
        deletionTargets = deletionTargets == null ? List.of() : List.copyOf(deletionTargets);
        if (ordinal < 0) {
            throw new IllegalArgumentException("ordinal must be >= 0");
        }
        if (actionType == ChangeActionType.DELETE) {
            if (insertion != null) {
                throw new IllegalArgumentException("deletions carry no insertion");
            }
        } else if (insertion == null) {
            throw new IllegalArgumentException("replacements/insertions require an insertion");
        }
        if (actionType != ChangeActionType.INSERT && placementLabel != null) {
            throw new IllegalArgumentException("only insertions carry a placement label");
        }
    }
}
