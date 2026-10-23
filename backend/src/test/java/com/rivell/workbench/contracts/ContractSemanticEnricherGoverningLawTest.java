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
    void fallbackStillReadsTheClauseWhenARegionFactExists() {
        var versionId = UUID.randomUUID();
        var regionFact = new MetadataFact(UUID.randomUUID(), versionId,
                MetadataField.GOVERNING_LAW, "State of Delaware",
                Interpretation.deterministic("governingLaw", "State of Delaware",
                        List.of(UUID.randomUUID())), null, null);
        var clause = clause(versionId, "7. Governing Law\n7.1 This Agreement is governed by "
                + "the laws of England and Wales.");

        var enricher = new ContractSemanticEnricher(null, null, null, null);
        var facts = enricher.governingLawFallback(List.of(regionFact), List.of(clause));

        assertThat(facts).singleElement()
                .extracting(MetadataFact::value)
                .isEqualTo("England and Wales");
    }

    @Test
    void aSectionSpecificClauseValueDoesNotReplaceAnAgreementWideRegionFact() {
        var versionId = UUID.randomUUID();
        var regionFact = new MetadataFact(UUID.randomUUID(), versionId,
                MetadataField.GOVERNING_LAW, "England and Wales",
                Interpretation.deterministic("governingLaw", "England and Wales",
                        List.of(UUID.randomUUID())), null, null);
        var clause = clause(versionId, "7. Governing Law\n7.1 This Section 1 is construed in "
                + "accordance with the laws of the State of New York.");

        var enricher = new ContractSemanticEnricher(null, null, null, null);
        var merged = enricher.applyGoverningLawFallback(List.of(regionFact), List.of(clause));

        assertThat(merged).singleElement()
                .extracting(MetadataFact::value)
                .isEqualTo("England and Wales");
    }

    @Test
    void aSectionSpecificSentenceAloneYieldsNoFact() {
        var clause = clause(UUID.randomUUID(), "7. Governing Law\n7.1 This Section 1 is "
                + "construed in accordance with the laws of the State of New York.");

        var enricher = new ContractSemanticEnricher(null, null, null, null);

        assertThat(enricher.governingLawFallback(List.of(), List.of(clause))).isEmpty();
    }

    @Test
    void clauseLevelValueReplacesTheRegionFactInsteadOfAddingASecond() {
        var versionId = UUID.randomUUID();
        var regionFact = new MetadataFact(UUID.randomUUID(), versionId,
                MetadataField.GOVERNING_LAW, "State of Delaware",
                Interpretation.deterministic("governingLaw", "State of Delaware",
                        List.of(UUID.randomUUID())), null, null);
        var clause = clause(versionId, "7. Governing Law\n7.1 This Agreement is governed by "
                + "the laws of England and Wales.");

        var enricher = new ContractSemanticEnricher(null, null, null, null);
        var merged = enricher.applyGoverningLawFallback(List.of(regionFact), List.of(clause));

        assertThat(merged).singleElement().satisfies(fact -> {
            assertThat(fact.field()).isEqualTo(MetadataField.GOVERNING_LAW);
            assertThat(fact.value()).isEqualTo("England and Wales");
            assertThat(fact.interpretation().sourceNodeIds())
                    .containsExactlyInAnyOrder(clause.headingNodeId(),
                            clause.textNodeIds().get(0));
        });
    }

    private static ClauseSemantics clause(UUID versionId, String text) {
        return new ClauseSemantics(UUID.randomUUID(), versionId, UUID.randomUUID(),
                ContractRegionSegmenter.SemanticRegionKind.CLAUSE, List.of(UUID.randomUUID()),
                text,
                new ConceptClassification(List.of(CoreConcepts.GOVERNING_LAW), List.of(),
                        Map.of(CoreConcepts.GOVERNING_LAW, 0.94)),
                List.of(), List.of(), FactCorrection.NONE, null);
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
