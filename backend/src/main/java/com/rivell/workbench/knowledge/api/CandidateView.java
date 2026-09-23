package com.rivell.workbench.knowledge.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.rivell.workbench.knowledge.CandidateType;
import com.rivell.workbench.knowledge.ConflictType;
import com.rivell.workbench.knowledge.ReviewStatus;

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
        String reviewNote,
        Instant reviewedAt,
        Instant createdAt,
        Instant updatedAt) {

    public record ConflictSignalView(
            ConflictType conflictType,
            String message,
            UUID publishedCandidateId) {
    }
}
