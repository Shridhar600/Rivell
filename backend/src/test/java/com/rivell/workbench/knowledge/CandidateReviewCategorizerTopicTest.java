package com.rivell.workbench.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.rivell.workbench.taxonomy.Concept;
import com.rivell.workbench.taxonomy.ConceptKey;
import com.rivell.workbench.taxonomy.ConceptRegistry;

/**
 * An entry whose own Topic column names the topic Rivell placed it under is placed. The model's
 * probability for that topic can sit between the topic threshold (0.5) and the concept
 * threshold (0.8); that alone must not make the review say the entry could not be placed.
 */
class CandidateReviewCategorizerTopicTest {

    private static final ConceptKey LEAF = ConceptKey.of("IP_INFRINGEMENT_INDEMNITY");

    private final CandidateReviewCategorizer categorizer = new CandidateReviewCategorizer(
            new SemanticPolicyProperties(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            new ConceptClassificationProperties(0.80),
            new ConceptRegistry(1, List.of(
                    new Concept(ConceptKey.of("LIABILITY_AND_INDEMNITY"), "Liability and indemnity",
                            null, null, List.of(), List.of(), 1),
                    new Concept(LEAF, "IP infringement indemnity",
                            ConceptKey.of("LIABILITY_AND_INDEMNITY"),
                            "The indemnity for third-party IP claims.",
                            List.of("IP Indemnity", "Infringement Indemnity"), List.of(), 2))));

    @Test
    void aTopicColumnThatNamesThePlacementByAliasIsNotLowConfidence() {
        var candidate = candidate(Map.of("Topic", "IP indemnity", "Clause text", "The Supplier shall defend."),
                LEAF.value(), 0.7);

        assertThat(categorizer.categorize(candidate, null).reasons())
                .doesNotContain(ReviewReason.LOW_CLASSIFICATION_CONFIDENCE);
    }

    @Test
    void aTopicColumnThatNamesThePlacementByLabelIsNotLowConfidence() {
        var candidate = candidate(Map.of("Topic", "IP infringement indemnity"), LEAF.value(), 0.6);

        assertThat(categorizer.categorize(candidate, null).reasons())
                .doesNotContain(ReviewReason.LOW_CLASSIFICATION_CONFIDENCE);
    }

    @Test
    void aTopicColumnThatNamesSomethingElseStaysLowConfidence() {
        var candidate = candidate(Map.of("Topic", "Payment terms"), LEAF.value(), 0.7);

        assertThat(categorizer.categorize(candidate, null).reasons())
                .contains(ReviewReason.LOW_CLASSIFICATION_CONFIDENCE);
    }

    @Test
    void aRecordWithNoTopicColumnStaysLowConfidenceBelowTheThreshold() {
        var candidate = candidate(Map.of("Clause text", "The Supplier shall defend."), LEAF.value(), 0.7);

        assertThat(categorizer.categorize(candidate, null).reasons())
                .contains(ReviewReason.LOW_CLASSIFICATION_CONFIDENCE);
    }

    @Test
    void aRecordPlacedUnderNoConceptIsLowConfidenceEvenWithATopicColumn() {
        var candidate = candidate(Map.of("Topic", "IP indemnity"), null, 0.4);

        assertThat(categorizer.categorize(candidate, null).reasons())
                .contains(ReviewReason.LOW_CLASSIFICATION_CONFIDENCE);
    }

    private static KnowledgeCandidate candidate(
            Map<String, String> columns, String conceptKey, double probability) {
        var now = Instant.now();
        var fields = new SemanticFields(Set.of(), null, null, null,
                new LinkedHashMap<>(Map.of(LEAF, probability)));
        return new KnowledgeCandidate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), CandidateType.CLAUSE_VARIANT, ReviewStatus.PENDING,
                "G-IND-01", "wording", new CandidatePayload(columns), conceptKey,
                conceptKey == null ? List.of() : List.of(conceptKey), null, null, null,
                fields, null, List.of(), null, null, null,
                new ApplicabilityScope(null, Set.of(), null, null, null, null, null),
                List.of(), null, null, null, null, now, now, null);
    }
}
