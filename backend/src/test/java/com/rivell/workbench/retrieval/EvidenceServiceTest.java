package com.rivell.workbench.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.rivell.workbench.knowledge.ApplicabilityScope;
import com.rivell.workbench.knowledge.CandidatePayload;
import com.rivell.workbench.knowledge.CandidateType;
import com.rivell.workbench.knowledge.KnowledgeCandidate;
import com.rivell.workbench.knowledge.PublishedCandidate;
import com.rivell.workbench.knowledge.ReviewStatus;
import com.rivell.workbench.knowledge.SemanticFields;

/** Omission labels are the title a reader sees, not the machine label. */
class EvidenceServiceTest {

    @Test
    void anOmissionLabelUsesTheCorrectedTitle() {
        var candidate = candidate("Machine title", "Corrected title");

        assertThat(EvidenceService.positionLabels(List.of(published(candidate))))
                .containsEntry(candidate.id(), "Corrected title");
    }

    @Test
    void anOmissionLabelFallsBackToTheMachineTitle() {
        var candidate = candidate("Machine title", null);

        assertThat(EvidenceService.positionLabels(List.of(published(candidate))))
                .containsEntry(candidate.id(), "Machine title");
    }

    private static PublishedCandidate published(KnowledgeCandidate candidate) {
        return new PublishedCandidate(candidate, UUID.randomUUID(), 1);
    }

    private static KnowledgeCandidate candidate(String rawLabel, String editedTitle) {
        var now = Instant.now();
        return new KnowledgeCandidate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), CandidateType.PLAYBOOK_RULE, ReviewStatus.PUBLISHED,
                rawLabel, "wording", CandidatePayload.EMPTY, null, List.of(), null, null, null,
                SemanticFields.EMPTY, null, List.of(), null, null, null,
                new ApplicabilityScope(null, Set.of(), null, null, null, null, null),
                List.of(), null, null, null, null, now, now, editedTitle);
    }
}
