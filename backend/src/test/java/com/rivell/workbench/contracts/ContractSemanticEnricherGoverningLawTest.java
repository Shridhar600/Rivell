package com.rivell.workbench.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.rivell.workbench.taxonomy.CoreConcepts;

class ContractSemanticEnricherGoverningLawTest {

    @Test
    void fallbackReadsTheWholeClauseWithoutAModelAndKeepsTheWordsAsWritten() {
        var versionId = UUID.randomUUID();
        var headingId = UUID.randomUUID();
        var textNodeId = UUID.randomUUID();
        var clause = new ClauseSemantics(UUID.randomUUID(), versionId, headingId,
                ContractRegionSegmenter.SemanticRegionKind.CLAUSE, List.of(textNodeId),
                "7. Governing Law\n7.1 This Agreement is governed by the laws of\n"
                        + "New South Wales, Australia.",
                new ConceptClassification(List.of(CoreConcepts.GOVERNING_LAW), List.of(),
                        Map.of(CoreConcepts.GOVERNING_LAW, 0.94)),
                List.of(), List.of(), FactCorrection.NONE, null);

        // null collaborators: the fallback must not reach for a model
        var enricher = new ContractSemanticEnricher(null, null, null, null);
        var facts = enricher.governingLawFallback(List.of(), List.of(clause));

        assertThat(facts).singleElement().satisfies(fact -> {
            assertThat(fact.field()).isEqualTo(MetadataField.GOVERNING_LAW);
            assertThat(fact.value()).isEqualTo("New South Wales");
            assertThat(fact.interpretation().kind()).isEqualTo(InterpretationKind.DETERMINISTIC);
            assertThat(fact.interpretation().sourceNodeIds())
                    .containsExactlyInAnyOrder(headingId, textNodeId);
        });
    }

    @Test
    void fallbackAddsNothingWhenTheClauseNamesNoJurisdiction() {
        var clause = new ClauseSemantics(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                ContractRegionSegmenter.SemanticRegionKind.CLAUSE, List.of(),
                "7. Governing Law\n7.1 The parties will discuss a governing law.",
                new ConceptClassification(List.of(CoreConcepts.GOVERNING_LAW), List.of(),
                        Map.of(CoreConcepts.GOVERNING_LAW, 0.91)),
                List.of(), List.of(), FactCorrection.NONE, null);

        var enricher = new ContractSemanticEnricher(null, null, null, null);

        assertThat(enricher.governingLawFallback(List.of(), List.of(clause))).isEmpty();
    }
}
