package com.rivell.workbench.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class SemanticConflictAnalyzerTest {

    @Test
    void longAndShortJurisdictionSpellingsAreTheSameScopeForExactDuplicates() {
        var organizationId = UUID.randomUUID();
        var candidate = candidate(organizationId, "United Kingdom");
        var peer = candidate(organizationId, "UK");

        var signals = new SemanticConflictAnalyzer(null, null)
                .inspect(candidate, List.of(peer));

        assertThat(signals).singleElement()
                .extracting(ConflictSignal::conflictType)
                .isEqualTo(ConflictType.DUPLICATE);
    }

    private static KnowledgeCandidate candidate(UUID organizationId, String jurisdiction) {
        var now = Instant.parse("2026-10-23T00:00:00Z");
        return new KnowledgeCandidate(UUID.randomUUID(), UUID.randomUUID(), organizationId,
                UUID.randomUUID(), CandidateType.PLAYBOOK_RULE, ReviewStatus.PENDING,
                "Rule", "Rule content", CandidatePayload.EMPTY, "LIMITATION_OF_LIABILITY",
                List.of(), null, null, null, SemanticFields.EMPTY, null, List.of(),
                null, null, null,
                new ApplicabilityScope("MSA", Set.of("MSA"), "Customer", "Third-Party",
                        jurisdiction, LocalDate.of(2026, 1, 1), null),
                List.of(), ReviewCategory.ROUTINE, Set.of(), null, null, now, now);
    }
}
