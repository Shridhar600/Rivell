package com.rivell.workbench.knowledge.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.rivell.workbench.knowledge.CandidateType;
import com.rivell.workbench.knowledge.ConflictType;
import com.rivell.workbench.knowledge.LegalConcept;
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
        String contentText,
        Map<String, String> payloadColumns,
        String legalConceptKey,
        Map<LegalConcept, Double> classifiedConcepts,
        Set<PositionRole> positions,
        PositionRole variantRole,
        String escalationTrigger,
        Boolean requiresApproval,
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
