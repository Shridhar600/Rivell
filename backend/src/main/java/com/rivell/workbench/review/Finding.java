package com.rivell.workbench.review;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.rivell.workbench.taxonomy.ConceptKey;

/**
 * Human-reviewable machine proposal: the consolidated issue of one analysis run.
 * Deterministically assembled from typed signals; no human resolution state (M8),
 * no ApprovedChange (M8). Subject references use stable canonical heading-node ids,
 * never per-enrichment clause rows. {@code addedBy} is the person who added a
 * {@link IssueFamily#USER_ADDED} finding, and null for every finding Rivell assembled.
 */
public record Finding(
        UUID id,
        UUID organizationId,
        UUID reviewId,
        UUID runId,
        String consolidationKey,
        FindingSubjectKind subjectKind,
        UUID subjectHeadingNodeId,
        ConceptKey absenceConcept,
        ConceptKey concept,
        ConceptKey secondaryConcept,
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
        Instant createdAt,
        List<ConceptKey> concepts,
        List<FindingClaim> claims,
        List<UUID> evidenceItemIds,
        List<UUID> signalIds,
        UUID addedBy) {

    public Finding {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(reviewId, "reviewId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(consolidationKey, "consolidationKey");
        Objects.requireNonNull(subjectKind, "subjectKind");
        Objects.requireNonNull(concept, "concept");
        Objects.requireNonNull(issueFamily, "issueFamily");
        Objects.requireNonNull(observationKind, "observationKind");
        Objects.requireNonNull(observationText, "observationText");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(reviewClassification, "reviewClassification");
        Objects.requireNonNull(proposedAction, "proposedAction");
        Objects.requireNonNull(proposedLanguageSource, "proposedLanguageSource");
        Objects.requireNonNull(createdAt, "createdAt");
        quality = quality == null ? Map.of() : Map.copyOf(quality);
        concepts = concepts == null ? List.of() : List.copyOf(concepts);
        claims = claims == null ? List.of() : List.copyOf(claims);
        evidenceItemIds = evidenceItemIds == null ? List.of() : List.copyOf(evidenceItemIds);
        signalIds = signalIds == null ? List.of() : List.copyOf(signalIds);
        if ((subjectKind == FindingSubjectKind.CLAUSE)
                != (subjectHeadingNodeId != null)) {
            throw new IllegalArgumentException(
                    "clause subjects require a heading node id and absence subjects none");
        }
        if ((subjectKind == FindingSubjectKind.ABSENCE) != (absenceConcept != null)) {
            throw new IllegalArgumentException(
                    "absence subjects require an absent concept and clause subjects none");
        }
        if ((proposedLanguageSource == ProposedLanguageSource.NONE)
                != (proposedLanguage == null)) {
            throw new IllegalArgumentException(
                    "proposed language is set iff its source is not NONE");
        }
        if ((issueFamily == IssueFamily.USER_ADDED) != (addedBy != null)) {
            throw new IllegalArgumentException(
                    "a person's finding records who added it, and only a person's finding does");
        }
        if (proposedLanguageSource == ProposedLanguageSource.APPROVED_DRAFTING
                && approvedDraftingEvidenceId == null) {
            throw new IllegalArgumentException(
                    "approved drafting proposals require the drafting evidence id");
        }
    }
}
