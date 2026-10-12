package com.rivell.workbench.review.api;

import java.time.Instant;
import java.util.UUID;

import com.rivell.workbench.review.FindingClassification;
import com.rivell.workbench.review.FindingSeverity;
import com.rivell.workbench.review.FindingSubjectKind;
import com.rivell.workbench.review.IssueFamily;
import com.rivell.workbench.review.ProposedAction;
import com.rivell.workbench.review.ProposedLanguageSource;

/**
 * Bounded list projection of one active-run finding. {@code relatedPosition} is true when the
 * deciding position governs a topic the clause only touches (a LOW note, not a breach).
 * {@code decidedBy}: who decided a policy-comparison finding — JEV, SECOND_OPINION (Jev and an
 * agreeing second opinion) or NEEDS_YOUR_LOOK (they disagreed or the second opinion was not
 * available, so the user decides); null for other findings.
 */
public record FindingSummaryView(
        UUID id,
        FindingSubjectKind subjectKind,
        UUID subjectHeadingNodeId,
        String absenceConcept,
        String concept,
        String secondaryConcept,
        IssueFamily issueFamily,
        String observationKind,
        FindingSeverity severity,
        FindingClassification reviewClassification,
        ProposedAction proposedAction,
        ProposedLanguageSource proposedLanguageSource,
        boolean relatedPosition,
        String decidedBy,
        Instant createdAt) {
}
