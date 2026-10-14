package com.rivell.workbench.review.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.rivell.workbench.review.ClaimKind;
import com.rivell.workbench.review.FindingClassification;
import com.rivell.workbench.review.FindingPositionComparison;
import com.rivell.workbench.review.FindingSeverity;
import com.rivell.workbench.review.FindingSubjectKind;
import com.rivell.workbench.review.IssueFamily;
import com.rivell.workbench.review.ProposedAction;
import com.rivell.workbench.review.ProposedLanguageSource;
import com.rivell.workbench.retrieval.api.RetrievalResponses;

/**
 * Finding detail: observation, proposal, typed quality, evidence-bound claims, and the
 * plan-check basis for absence findings. {@code evidenceItems} are the exact frozen
 * items the finding cites (G-M7-1), resolved from the finding's run and never rebuilt
 * from latest knowledge; every claim evidence id resolves inside this set. Signal ids
 * are provenance references only; no raw signal surface is exposed. {@code addedBy} is the
 * user who added a {@code USER_ADDED} finding, null for findings Rivell assembled.
 * {@code firstReviewCategory}: the first review's category on a needs-your-look item; null otherwise.
 * {@code positionLabel}: the playbook position label of an omission finding; null otherwise.
 */
public record FindingDetailView(
        UUID id,
        UUID reviewId,
        UUID runId,
        FindingSubjectKind subjectKind,
        UUID subjectHeadingNodeId,
        String absenceConcept,
        String concept,
        String secondaryConcept,
        List<String> concepts,
        IssueFamily issueFamily,
        String observationKind,
        String observationText,
        FindingSeverity severity,
        FindingClassification reviewClassification,
        ProposedAction proposedAction,
        String proposedLanguage,
        ProposedLanguageSource proposedLanguageSource,
        UUID approvedDraftingEvidenceId,
        Map<String, Object> quality,
        List<ClaimView> claims,
        List<UUID> evidenceItemIds,
        List<RetrievalResponses.EvidenceItemResponse> evidenceItems,
        FindingPositionComparison positionComparison,
        List<UUID> signalIds,
        AbsenceBasisView absenceBasis,
        Instant createdAt,
        String addedBy,
        String firstReviewCategory,
        String positionLabel) {

    public record ClaimView(
            int ordinal,
            ClaimKind claimKind,
            String text,
            boolean absence,
            List<UUID> evidenceItemIds) {
    }

    public record AbsenceBasisView(UUID planCheckId, String checkKey) {
    }
}
