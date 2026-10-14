package com.rivell.workbench.review.check;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.rivell.workbench.contracts.ContractType;
import com.rivell.workbench.contracts.ReviewProfile;
import com.rivell.workbench.retrieval.AuthorityClass;
import com.rivell.workbench.retrieval.EvidenceBundle;
import com.rivell.workbench.retrieval.EvidenceItem;
import com.rivell.workbench.retrieval.EvidenceRequest;
import com.rivell.workbench.retrieval.EvidenceSufficiency;
import com.rivell.workbench.retrieval.KnowledgePin;
import com.rivell.workbench.retrieval.LaneInfo;
import com.rivell.workbench.taxonomy.ConceptKey;

class ClauseDefinitionsTest {

    @Test
    void atMostEightDefinitionsKeepBundleOrder() {
        var items = new ArrayList<EvidenceItem>();
        for (int index = 1; index <= 10; index++) {
            items.add(definition("Term " + index, "definition " + index));
        }

        var definitions = ClauseDefinitions.of(bundle(items));

        assertThat(definitions.texts()).hasSize(8);
        assertThat(definitions.texts().getFirst()).isEqualTo("Term 1 means definition 1");
        assertThat(definitions.texts().getLast()).isEqualTo("Term 8 means definition 8");
        assertThat(definitions.terms()).containsExactly(
                "Term 1", "Term 2", "Term 3", "Term 4", "Term 5", "Term 6", "Term 7", "Term 8");
        assertThat(definitions.evidenceIds()).hasSize(8)
                .containsExactlyElementsOf(items.subList(0, 8).stream()
                        .map(EvidenceItem::evidenceId).toList());
    }

    @Test
    void aDefinitionTextIsCutToOneThousandCharacters() {
        var longText = "Long means " + "x".repeat(1500 - "Long means ".length());
        var items = List.of(new EvidenceItem(UUID.randomUUID(), AuthorityClass.CURRENT_CONTRACT,
                "CURRENT_CONTRACT_DEFINED_TERM", null, null, UUID.randomUUID(), null, null, null,
                longText, null, List.of(), "DEFINED_TERM", LaneInfo.NONE, null, null, List.of(),
                null, null));

        var definitions = ClauseDefinitions.of(bundle(items));

        assertThat(definitions.texts().getFirst()).hasSize(1000);
        assertThat(definitions.texts().getFirst()).startsWith("Long means ");
        assertThat(definitions.terms()).containsExactly("Long");
    }

    @Test
    void itemsOfOtherReasonsAreIgnored() {
        var canonical = new EvidenceItem(UUID.randomUUID(), AuthorityClass.CURRENT_CONTRACT,
                "CURRENT_CONTRACT_CLAUSE", null, null, UUID.randomUUID(), null, null, null,
                "The clause itself", null, List.of(), "CANONICAL_STRUCTURE", LaneInfo.NONE, null,
                null, List.of(), null, null);
        var term = definition("Fees", "the fees paid");

        var definitions = ClauseDefinitions.of(bundle(List.of(canonical, term)));

        assertThat(definitions.texts()).containsExactly("Fees means the fees paid");
        assertThat(definitions.evidenceIds()).containsExactly(term.evidenceId());
    }

    private static EvidenceItem definition(String term, String definition) {
        return new EvidenceItem(UUID.randomUUID(), AuthorityClass.CURRENT_CONTRACT,
                "CURRENT_CONTRACT_DEFINED_TERM", null, null, UUID.randomUUID(), null, null, null,
                term + " means " + definition, null, List.of(), "DEFINED_TERM", LaneInfo.NONE, null,
                null, List.of(), null, null);
    }

    private static EvidenceBundle bundle(List<EvidenceItem> items) {
        var request = EvidenceRequest.forClauseReview(UUID.randomUUID(), ConceptKey.of("GENERAL"),
                UUID.randomUUID(), 1, UUID.randomUUID(), "clause text",
                ReviewProfile.minimal(ContractType.MSA), LocalDate.of(2026, 1, 1),
                KnowledgePin.none());
        return new EvidenceBundle(UUID.randomUUID(), request, List.of(), List.of(), List.of(),
                items, List.of(), EvidenceSufficiency.POLICY_MISSING, List.of(), null);
    }
}
