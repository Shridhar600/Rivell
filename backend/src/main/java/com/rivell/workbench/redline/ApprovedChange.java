package com.rivell.workbench.redline;

import java.util.Objects;
import java.util.UUID;

import com.rivell.workbench.taxonomy.ConceptKey;

/**
 * One deterministic legal-change intent frozen for M9: exact action, canonical target,
 * and exact approved language with provenance. M9 resolves concrete OpenXML spans; no
 * legal reasoning happens there.
 */
public record ApprovedChange(
        UUID id,
        UUID organizationId,
        UUID changeSetId,
        UUID reviewId,
        int ordinal,
        UUID findingId,
        UUID decisionId,
        ChangeActionType actionType,
        ChangeTargetKind targetKind,
        UUID targetHeadingNodeId,
        ConceptKey absenceConcept,
        String approvedLanguage,
        ChangeLanguageSource languageSource,
        UUID approvedDraftingEvidenceId) {

    public ApprovedChange {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(changeSetId, "changeSetId");
        Objects.requireNonNull(reviewId, "reviewId");
        Objects.requireNonNull(findingId, "findingId");
        Objects.requireNonNull(decisionId, "decisionId");
        Objects.requireNonNull(actionType, "actionType");
        Objects.requireNonNull(targetKind, "targetKind");
        Objects.requireNonNull(languageSource, "languageSource");
        if (ordinal < 0) {
            throw new IllegalArgumentException("ordinal must be >= 0");
        }
        if ((targetKind == ChangeTargetKind.CLAUSE) != (targetHeadingNodeId != null)) {
            throw new IllegalArgumentException(
                    "clause targets require a heading node id and absence targets none");
        }
        if ((targetKind == ChangeTargetKind.ABSENCE) != (absenceConcept != null)) {
            throw new IllegalArgumentException(
                    "absence targets require a concept and clause targets none");
        }
        if ((actionType == ChangeActionType.INSERT) != (targetKind == ChangeTargetKind.ABSENCE)) {
            throw new IllegalArgumentException("INSERT targets an absence; REPLACE/DELETE a clause");
        }
        if (actionType == ChangeActionType.DELETE) {
            if (approvedLanguage != null || languageSource != ChangeLanguageSource.NONE) {
                throw new IllegalArgumentException("deletions carry no language");
            }
        } else if (approvedLanguage == null || languageSource == ChangeLanguageSource.NONE) {
            throw new IllegalArgumentException("replacements/insertions require approved language");
        }
        if ((languageSource == ChangeLanguageSource.APPROVED_DRAFTING)
                != (approvedDraftingEvidenceId != null)) {
            throw new IllegalArgumentException(
                    "drafting evidence id is set iff the source is approved drafting");
        }
    }
}
