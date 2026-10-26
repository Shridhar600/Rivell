package com.rivell.workbench.taxonomy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.rivell.workbench.intelligence.ChoiceAnswer;
import com.rivell.workbench.intelligence.ChoiceQuestion;
import com.rivell.workbench.intelligence.DecisionExecutionRequest;
import com.rivell.workbench.intelligence.ModelBinding;
import com.rivell.workbench.intelligence.ProviderExecutionResult;
import com.rivell.workbench.intelligence.SemanticAnswer;
import com.rivell.workbench.intelligence.SemanticDecisionProvider;
import com.rivell.workbench.intelligence.SemanticDecisionService;
import com.rivell.workbench.intelligence.TokenUsage;
import com.rivell.workbench.intelligence.YesNoAnswer;

/**
 * A heading that names exactly one of the topics the classifier passed is carried as
 * the heading topic. It never becomes the primary: the model's own choice, or no primary when
 * the model gave none, stays the primary.
 */
class TopicClassifierHeadingTest {

    private static final ConceptKey TERM = ConceptKey.of("TERM");
    private static final ConceptKey TERMINATION = ConceptKey.of("TERMINATION_FOR_CAUSE");
    private static final ConceptKey TERMINATION_CONVENIENCE =
            ConceptKey.of("TERMINATION_FOR_CONVENIENCE");
    private static final ConceptKey SURVIVAL = ConceptKey.of("SURVIVAL");
    private static final ConceptKey GOVERNING_LAW = ConceptKey.of("GOVERNING_LAW");
    private static final ConceptKey CONFIDENTIALITY = ConceptKey.of("CONFIDENTIALITY_OBLIGATIONS");
    private static final ConceptKey CONFIDENTIALITY_PERIOD = ConceptKey.of("CONFIDENTIALITY_PERIOD");
    private static final ConceptKey DATA_PROTECTION_LEAF = ConceptKey.of("PROCESSING_DETAILS");
    private static final ConceptKey LIABILITY = ConceptKey.of("LIMITATION_OF_LIABILITY");
    private static final ConceptKey INDEMNITY = ConceptKey.of("INDEMNIFICATION");

    @Test
    void headingTopicNamesTheOnlyPassedTopicWithoutBecomingPrimary() {
        var result = classify("XIV GOVERNING LAW", Map.of(TERM, 0.95, GOVERNING_LAW, 0.95));

        assertThat(result.primaryTopic()).isEmpty();
        assertThat(result.headingTopic()).contains(GOVERNING_LAW);
    }

    @Test
    void headingNamingTwoPassedTopicsUsesTheFirstNounPhrase() {
        var result = classify("XIII TERM AND TERMINATION",
                Map.of(TERM, 0.95, TERMINATION, 0.95));

        assertThat(result.primaryTopic()).isEmpty();
        assertThat(result.headingTopic()).contains(TERM);
    }

    @Test
    void termAndSurvivalHeadingCarriesTermWithoutBecomingPrimary() {
        var result = classify("Term and Survival", Map.of(TERM, 0.95, SURVIVAL, 0.95));

        assertThat(result.primaryTopic()).isEmpty();
        assertThat(result.headingTopic()).contains(TERM);
    }

    @Test
    void limitationAndIndemnityHeadingCarriesLimitation() {
        var result = classify("Limitation of Liability and Indemnity",
                Map.of(LIABILITY, 0.95, INDEMNITY, 0.95));

        assertThat(result.primaryTopic()).isEmpty();
        assertThat(result.headingTopic()).contains(LIABILITY);
    }

    @Test
    void confidentialityAndDataProtectionHeadingNamesNothing() {
        var result = classify("Confidentiality and Data Protection",
                Map.of(CONFIDENTIALITY, 0.95, DATA_PROTECTION_LEAF, 0.95));

        assertThat(result.primaryTopic()).as("no leaf label or alias names the first phrase").isEmpty();
        assertThat(result.headingTopic()).isEmpty();
        assertThat(result.secondaryTopics()).contains(CONFIDENTIALITY, DATA_PROTECTION_LEAF);
    }

    @Test
    void exactMultiWordAliasCarriesEvenAcrossAnd() {
        var result = classify("Use and Protection of Confidential Information",
                Map.of(CONFIDENTIALITY, 0.95, TERM, 0.95));

        assertThat(result.primaryTopic()).isEmpty();
        assertThat(result.headingTopic()).contains(CONFIDENTIALITY);
    }

    @Test
    void longerHeadingPhraseBeatsAShorterAlias() {
        var result = classify("Termination for Convenience",
                Map.of(TERMINATION, 0.95, TERMINATION_CONVENIENCE, 0.95));

        assertThat(result.primaryTopic()).isEmpty();
        assertThat(result.headingTopic()).contains(TERMINATION_CONVENIENCE);
    }

    @Test
    void genericDurationAliasDoesNotHijackConfidentialityPeriod() {
        var result = classify("Duration of Confidentiality",
                Map.of(TERM, 0.95, CONFIDENTIALITY_PERIOD, 0.95));

        assertThat(result.primaryTopic()).isEmpty();
        assertThat(result.headingTopic()).contains(CONFIDENTIALITY_PERIOD);
        assertThat(classify("Duration of Confidentiality", Map.of(TERM, 0.95, INDEMNITY, 0.95))
                .headingTopic()).isEmpty();
    }

    @Test
    void neverOverridesTheModelChosenPrimary() {
        var result = classify("Governing Law", Map.of(TERM, 0.95, GOVERNING_LAW, 0.95),
                TERM);

        assertThat(result.primaryTopic()).contains(TERM);
        assertThat(result.headingTopic()).contains(GOVERNING_LAW);
    }

    @Test
    void neverCarriesATopicTheClassifierDidNotPass() {
        var result = classify("Governing Law", Map.of(TERM, 0.95, INDEMNITY, 0.95));

        assertThat(result.topics()).containsExactlyInAnyOrder(TERM, INDEMNITY);
        assertThat(result.primaryTopic()).isEmpty();
        assertThat(result.headingTopic()).isEmpty();
    }

    @Test
    void wholeWordsOnlySoTermIsNotReadInsideTermination() {
        var result = classify("Termination", Map.of(TERM, 0.95, GOVERNING_LAW, 0.95));

        assertThat(result.primaryTopic()).isEmpty();
        assertThat(result.headingTopic()).isEmpty();
    }

    @Test
    void nullHeadingCarriesNothingAndNumberingIsStripped() {
        assertThat(classify(null, Map.of(TERM, 0.95, GOVERNING_LAW, 0.95)).headingTopic())
                .isEmpty();
        assertThat(classify("I. Governing Law", Map.of(TERM, 0.95, GOVERNING_LAW, 0.95))
                .headingTopic()).contains(GOVERNING_LAW);
    }

    private static TopicClassification classify(
            String heading, Map<ConceptKey, Double> passed) {
        return classify(heading, passed, null);
    }

    private static TopicClassification classify(
            String heading, Map<ConceptKey, Double> passed, ConceptKey modelPrimary) {
        var provider = new ScriptedProvider(passed, modelPrimary);
        var decisions = new SemanticDecisionService(
                asset -> new ModelBinding("fake", "fake-decision-model"),
                providerId -> Optional.of(provider),
                Clock.fixed(Instant.parse("2026-10-23T00:00:00Z"), ZoneOffset.UTC));
        var classifier = new TopicClassifier(
                registry(), decisions, new TaxonomyProperties(0.5, 0.8, 0.10, 4000, 8));
        return classifier.classify(heading, "Text of the section.");
    }

    private static ConceptRegistry registry() {
        var term = ConceptKey.of("TERM_AND_TERMINATION");
        var disputes = ConceptKey.of("DISPUTES_AND_LAW");
        var confidentiality = ConceptKey.of("CONFIDENTIALITY");
        var data = ConceptKey.of("DATA_PROTECTION");
        var liability = ConceptKey.of("LIABILITY_AND_INDEMNITY");
        return new ConceptRegistry(1, List.of(
                parent(term, "Term and termination", 1),
                parent(disputes, "Disputes and law", 2),
                parent(confidentiality, "Confidentiality", 3),
                parent(data, "Data protection", 4),
                parent(liability, "Liability and indemnity", 5),
                leaf(TERM, "Term", term, List.of("Term", "Duration"), 10),
                leaf(TERMINATION, "Termination for cause", term,
                        List.of("Termination", "Termination for Breach"), 11),
                leaf(SURVIVAL, "Survival", term, List.of("Survival", "Surviving Provisions"), 12),
                leaf(GOVERNING_LAW, "Governing law", disputes,
                        List.of("Governing Law", "Choice of Law"), 13),
                leaf(CONFIDENTIALITY, "Confidentiality obligations", confidentiality,
                        List.of("Use and Protection of Confidential Information"), 14),
                leaf(DATA_PROTECTION_LEAF, "Details of processing", data,
                        List.of("Data Protection"), 15),
                leaf(LIABILITY, "Limitation of liability", liability,
                        List.of("Limitation of Liability", "Liability Cap"), 16),
                leaf(INDEMNITY, "Indemnification", liability,
                        List.of("Indemnification", "Indemnity"), 17),
                leaf(TERMINATION_CONVENIENCE, "Termination for convenience", term,
                        List.of("Termination for Convenience", "Early Termination"), 18),
                leaf(CONFIDENTIALITY_PERIOD, "Confidentiality period", confidentiality,
                        List.of("Confidentiality Period", "Duration of Confidentiality"), 19)));
    }

    private static Concept parent(ConceptKey key, String label, int sortOrder) {
        return new Concept(key, label, null, null, List.of(), List.of(), sortOrder);
    }

    private static Concept leaf(
            ConceptKey key, String label, ConceptKey parent, List<String> aliases, int sortOrder) {
        return new Concept(key, label, parent, "Definition of " + label + ".", aliases,
                List.of(), sortOrder);
    }

    /** Every topic answers its scripted probability; the primary choice is uniform unless scripted. */
    private static final class ScriptedProvider implements SemanticDecisionProvider {

        private final Map<ConceptKey, Double> passed;
        private final ConceptKey modelPrimary;

        private ScriptedProvider(Map<ConceptKey, Double> passed, ConceptKey modelPrimary) {
            this.passed = passed;
            this.modelPrimary = modelPrimary;
        }

        @Override
        public String id() {
            return "fake";
        }

        @Override
        public ProviderExecutionResult execute(DecisionExecutionRequest request) {
            var answers = new LinkedHashMap<String, SemanticAnswer>();
            for (var entry : request.pack().questions().entrySet()) {
                if (entry.getValue() instanceof ChoiceQuestion choice) {
                    answers.put(entry.getKey(), primaryChoice(choice));
                } else {
                    var probability = 0.02;
                    for (var topic : passed.entrySet()) {
                        if (TopicClassifier.questionName(topic.getKey()).equals(entry.getKey())) {
                            probability = topic.getValue();
                        }
                    }
                    answers.put(entry.getKey(), new YesNoAnswer(probability));
                }
            }
            return new ProviderExecutionResult(answers, "fake-model", new TokenUsage(1, 1));
        }

        private ChoiceAnswer primaryChoice(ChoiceQuestion choice) {
            var probabilities = new LinkedHashMap<String, Double>();
            var selected = modelPrimary == null
                    ? choice.criteria().keySet().iterator().next()
                    : modelPrimary.value();
            for (var option : choice.criteria().keySet()) {
                probabilities.put(option, modelPrimary == null
                        ? 1.0 / choice.criteria().size() : option.equals(selected) ? 0.95 : 0.01);
            }
            return new ChoiceAnswer(selected, probabilities.get(selected), probabilities);
        }
    }
}
