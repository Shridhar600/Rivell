package com.rivell.workbench.knowledge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.rivell.workbench.knowledge.ApplicabilityScope;
import com.rivell.workbench.knowledge.CandidatePayload;
import com.rivell.workbench.knowledge.CandidateType;
import com.rivell.workbench.knowledge.KnowledgeCandidate;
import com.rivell.workbench.knowledge.ReviewCategory;
import com.rivell.workbench.knowledge.ReviewReason;
import com.rivell.workbench.knowledge.ReviewStatus;
import com.rivell.workbench.knowledge.SemanticFields;

/** The K scorer counts the needs-attention rate per playbook from this per-entry state. */
class CandidateViewTest {

    @Test
    void needsAttentionIsTrueOnlyForTheCompletenessGateReason() {
        var withGap = candidate(Set.of(ReviewReason.UNMAPPED_AUTHORITY_WORDS));
        var clean = candidate(Set.of(ReviewReason.LOW_CLASSIFICATION_CONFIDENCE));

        assertThat(KnowledgeController.toCandidateView(withGap).needsAttention()).isTrue();
        assertThat(KnowledgeController.toCandidateView(clean).needsAttention()).isFalse();
    }

    private static KnowledgeCandidate candidate(Set<ReviewReason> reasons) {
        var now = Instant.now();
        return new KnowledgeCandidate(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                CandidateType.PLAYBOOK_RULE, ReviewStatus.PENDING, "Payment terms", "Net 45.",
                new CandidatePayload(Map.of()), null, List.of(), null, null, null,
                new SemanticFields(Set.of(), null, null, null, Map.of()),
                null, List.of(), null, null, null,
                new ApplicabilityScope(null, Set.of(), null, null, null, null, null),
                List.of(), ReviewCategory.ELEVATED, reasons, null, null, now, now, null);
    }
}
