package com.rivell.workbench.knowledge.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.rivell.workbench.knowledge.CandidateType;
import com.rivell.workbench.knowledge.AuthorityConflict;
import com.rivell.workbench.knowledge.AuthorityRule;
import com.rivell.workbench.knowledge.ConflictType;
import com.rivell.workbench.knowledge.PositionRole;
import com.rivell.workbench.knowledge.ReviewCategory;
import com.rivell.workbench.knowledge.ReviewReason;
import com.rivell.workbench.knowledge.ReviewStatus;
import com.rivell.workbench.knowledge.VerificationState;

/**
 * Product-facing candidate view: raw source, machine semantic fields, human edits,
 * normalized concepts, field provenance summaries, review risk, and conflict signals.
 * Exposes what Rivell believes, where it came from, how verified it is, and what the
 * human must review — never provider internals.
 */
public record CandidateView(
        UUID id,
        UUID artifactId,
        UUID extractionUnitId,
        CandidateType candidateType,
        ReviewStatus reviewStatus,
        String rawLabel,
        /** A title a person typed; null until one is given. {@code rawLabel} stays as read. */
        String editedTitle,
        /** The title to show and publish: the person's when there is one, else {@code rawLabel}. */
        String title,
        String contentText,
        Map<String, String> payloadColumns,
        /** Role of each payload label (PREFERRED, FALLBACK, APPLICABILITY, ...), known or resolved. */
        Map<String, String> payloadFieldRoles,
        String legalConceptKey,
        String conceptCorrection,
        UUID conceptCorrectedBy,
        Instant conceptCorrectedAt,
        String effectiveConceptKey,
        Map<String, Double> classifiedConcepts,
        Set<PositionRole> positions,
        PositionRole variantRole,
        String escalationTrigger,
        Boolean requiresApproval,
        /** The preferred wording the source states; null when it states none. */
        String preferredText,
        /** Every fallback wording the source states, in source order. */
        List<String> fallbackTexts,
        /** The walk-away or prohibited wording the source states; null when it states none. */
        String prohibitedText,
        /** Typed approval rules, one per stance, with their source level and quote. */
        List<AuthorityRule> authorityRules,
        /** Lower-level rules naming a different approver for the same stance. */
        List<AuthorityConflict> authorityConflicts,
        /** Label-shaped walk-away or approver words the stage could not map. */
        List<String> unmappedAuthority,
        /** True while the entry needs attention for an unmapped walk-away or approver word. */
        boolean needsAttention,
        /** Structured fields a person corrected and saved; an unapplied machine proposal is not one. */
        Set<String> correctedFields,
        List<InterpretationView> interpretations,
        String editedContentText,
        Map<String, String> editedPayloadColumns,
        UUID supersedingCandidateId,
        String contractPackKey,
        Set<String> declaredContractTypes,
        String partyPerspective,
        String paperSource,
        String jurisdiction,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        /** Contract types the record itself states it applies to; empty when it states none. */
        Set<String> sourceContractTypes,
        List<ConflictSignalView> conflictSignals,
        ReviewCategory reviewCategory,
        Set<ReviewReason> reviewReasons,
        String reviewNote,
        Instant reviewedAt,
        Instant createdAt,
        Instant updatedAt) {

    public record ConflictSignalView(
            ConflictType conflictType,
            String message,
            UUID publishedCandidateId) {
    }

    /**
     * Provenance summary of one interpreted field: what the machine proposed, how it was
     * produced, how many source nodes support it, and its verification state.
     */
    public record InterpretationView(
            String field,
            String proposedValue,
            String kind,
            String specId,
            int specVersion,
            int supportingSourceNodes,
            VerificationState verification) {
    }
}
