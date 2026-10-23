package com.rivell.workbench.taxonomy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.rivell.workbench.intelligence.ChoiceAnswer;
import com.rivell.workbench.intelligence.ChoiceQuestion;
import com.rivell.workbench.intelligence.DecisionExecution;
import com.rivell.workbench.intelligence.DecisionSpec;
import com.rivell.workbench.intelligence.ProviderProtocolException;
import com.rivell.workbench.intelligence.QuestionPack;
import com.rivell.workbench.intelligence.SemanticDecisionService;
import com.rivell.workbench.intelligence.SemanticQuestion;
import com.rivell.workbench.intelligence.SemanticState;
import com.rivell.workbench.intelligence.YesNoAnswer;
import com.rivell.workbench.intelligence.YesNoQuestion;

/**
 * Classifies one text section (a contract clause or a knowledge unit) against every leaf
 * topic in a single Jev call: one independent yes/no per topic, since a section may
 * express several. When two or more topics pass, a second call picks the main one.
 *
 * <p>Measured on the dev corpus (2026-10-10): all leaves in one call beat a
 * family-then-leaf shortlist on accuracy and took one call instead of two.
 */
public final class TopicClassifier {

    static final String TOPICS_SPEC = "section-topics";
    static final String PRIMARY_SPEC = "section-primary-topic";
    private static final String QUESTION_PREFIX = "topic_";

    private final ConceptRegistry registry;
    private final SemanticDecisionService decisions;
    private final TaxonomyProperties properties;
    private final DecisionSpec topicsSpec;

    private static final Pattern HEADING_NUMBER = Pattern.compile(
            "^\\s*(?:(?:clause|section|article)\\s+)?"
                    + "(?:\\d+(?:\\.\\d+)*|[IVXLCDM]+|[A-Z]-\\d+[A-Za-z]?|[A-Z])"
                    + "(?:\\s*[.):]\\s*|\\s+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FIRST_PHRASE_BREAK = Pattern.compile(
            "\\s+(?:and|or)\\s+|\\s*[,;/]\\s*");

    public TopicClassifier(
            ConceptRegistry registry, SemanticDecisionService decisions, TaxonomyProperties properties) {
        this.registry = registry;
        this.decisions = decisions;
        this.properties = properties;
        this.topicsSpec = topicsSpec(registry);
    }

    /**
     * @throws com.rivell.workbench.intelligence.SemanticDecisionException when the provider
     *     fails; callers decide how to degrade.
     */
    public TopicClassification classify(String heading, String text) {
        var state = new SectionState(heading, truncate(text));
        var topicsExecution = decisions.evaluate(topicsSpec, state);
        var answers = topicsExecution.answers();
        var probabilities = new LinkedHashMap<ConceptKey, Double>();
        for (var leaf : registry.leaves()) {
            var name = questionName(leaf.key());
            if (!(answers.get(name) instanceof YesNoAnswer yesNo)) {
                throw new ProviderProtocolException("Missing yes/no answer " + name);
            }
            probabilities.put(leaf.key(), yesNo.probability());
        }
        var topics = probabilities.entrySet().stream()
                .filter(entry -> entry.getValue() >= properties.topicThreshold())
                .sorted(Map.Entry.<ConceptKey, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .toList();
        var questions = registry.leaves().size();
        if (topics.size() <= 1) {
            var primary = topics.isEmpty() ? null : topics.getFirst();
            return new TopicClassification(topics, primary, probabilities, registry.version(),
                    List.of(topicsExecution.metadata()), questions);
        }
        var candidates = topics.subList(0, Math.min(topics.size(), properties.maxPrimaryCandidates()));
        var primaryExecution = decisions.evaluate(primarySpec(candidates), state);
        var modelPrimary = primaryTopic(primaryExecution, candidates);
        return new TopicClassification(topics,
                modelPrimary != null ? modelPrimary : headingPrimary(heading, topics),
                probabilities, registry.version(),
                List.of(topicsExecution.metadata(), primaryExecution.metadata()),
                questions + 1);
    }

    /**
     * DW-098: a numbered heading that names its topic ("XIII TERM AND TERMINATION") decides
     * the primary when the model expressed no clear main subject. Only a topic that already
     * passed the classifier can be promoted, and a model-chosen primary is never overridden.
     * A phrase of any topic shadows a shorter phrase it contains, so "Termination for
     * Convenience" does not fall back to the generic "Termination" alias and "Duration of
     * Confidentiality" does not fall back to "Duration". The whole heading decides when it
     * is exactly a label or alias; otherwise its first noun phrase must name exactly one
     * passed topic. A topic named only in a later phrase is not promoted ("Confidentiality
     * and Data Protection" stays without a primary when only Data Protection matches), so a
     * heading never hands the section to a topic that is not its main subject. Matching is
     * by whole taxonomy label or alias, so "Term" does not match inside "Termination".
     */
    private ConceptKey headingPrimary(String heading, List<ConceptKey> topics) {
        if (heading == null || heading.isBlank() || topics.isEmpty()) {
            return null;
        }
        var normalized = normalizeHeading(heading);
        if (normalized.isEmpty()) {
            return null;
        }
        var exact = maximalMatches(normalized).stream()
                .filter(match -> match.phrase().equals(normalized))
                .map(PhraseMatch::topic)
                .distinct()
                .toList();
        if (!exact.isEmpty()) {
            return exact.size() == 1 && topics.contains(exact.getFirst())
                    ? exact.getFirst() : null;
        }
        var firstPhrase = firstPhrase(normalized);
        var namedInFirstPhrase = maximalMatches(firstPhrase).stream()
                .map(PhraseMatch::topic)
                .distinct()
                .filter(topics::contains)
                .toList();
        return namedInFirstPhrase.size() == 1 ? namedInFirstPhrase.getFirst() : null;
    }

    private List<PhraseMatch> maximalMatches(String text) {
        if (text.isEmpty()) {
            return List.of();
        }
        var matches = new ArrayList<PhraseMatch>();
        for (var concept : registry.leaves()) {
            addMatch(matches, concept.key(), concept.label(), text);
            for (var alias : concept.aliases()) {
                addMatch(matches, concept.key(), alias, text);
            }
        }
        return matches.stream()
                .distinct()
                .filter(match -> matches.stream().noneMatch(other ->
                        !other.phrase().equals(match.phrase())
                                && containsPhrase(other.phrase(), match.phrase())))
                .toList();
    }

    private static void addMatch(
            List<PhraseMatch> matches, ConceptKey topic, String phrase, String text) {
        var normalized = normalize(phrase);
        if (!normalized.isEmpty() && containsPhrase(text, normalized)) {
            matches.add(new PhraseMatch(topic, normalized));
        }
    }

    private static boolean containsPhrase(String text, String phrase) {
        return (" " + text + " ").contains(" " + phrase + " ");
    }

    private static String firstPhrase(String heading) {
        var matcher = FIRST_PHRASE_BREAK.matcher(heading);
        return matcher.find() ? heading.substring(0, matcher.start()).strip() : heading;
    }

    private record PhraseMatch(ConceptKey topic, String phrase) {
    }

    /** Lowercase, numbering and punctuation stripped; "&" reads as "and". */
    private static String normalizeHeading(String heading) {
        return normalize(HEADING_NUMBER.matcher(heading).replaceFirst(""));
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT)
                .replace("&", " and ")
                .replaceAll("[^a-z0-9]+", " ")
                .strip();
    }

    /**
     * Every leaf of the family a heading names ("Data protection", "Data protection and
     * privacy", "G-03 Confidentiality"); empty when it names none. A playbook position titled
     * with a whole family covers the family: its text spreads over several leaves, so no single
     * leaf passes the topic threshold and the position would otherwise meet no clause (dev2
     * eval, 2026-10-12). Deterministic; a leaf label ("Confidentiality period") is no family.
     */
    public List<ConceptKey> familyLeaves(String heading) {
        if (heading == null) {
            return List.of();
        }
        var normalized = heading.toLowerCase(java.util.Locale.ROOT)
                .replaceFirst("^\\s*(?:[a-z]{1,3}[- ]?\\d+[a-z]?|\\d+(?:\\.\\d+)*)[.:)]?\\s+", "")
                .replaceAll("[^a-z0-9& ]", " ").replaceAll("\\s+", " ").strip();
        for (var family : registry.parents()) {
            var label = family.label().toLowerCase(java.util.Locale.ROOT);
            if (normalized.equals(label) || normalized.startsWith(label + " and ")
                    || normalized.startsWith(label + " & ")) {
                return registry.leaves().stream()
                        .filter(leaf -> family.key().equals(leaf.parentKey()))
                        .map(com.rivell.workbench.taxonomy.Concept::key)
                        .toList();
            }
        }
        return List.of();
    }

    private DecisionSpec primarySpec(List<ConceptKey> candidates) {
        var criteria = new LinkedHashMap<String, String>();
        for (var key : candidates) {
            var concept = registry.require(key);
            criteria.put(key.value(), concept.label() + ": " + concept.definition());
        }
        return new DecisionSpec(PRIMARY_SPEC, registry.version(),
                "Choose the main subject of a section among the topics it expresses.",
                SectionState.SCHEMA_VERSION,
                new QuestionPack(Map.of("primary_topic", new ChoiceQuestion(
                        "Which single topic is the main subject of this section? "
                                + "The section may also deal with the others in passing.",
                        criteria))));
    }

    private ConceptKey primaryTopic(DecisionExecution execution, List<ConceptKey> candidates) {
        if (!(execution.answers().get("primary_topic") instanceof ChoiceAnswer answer)) {
            throw new ProviderProtocolException("Missing primary topic answer");
        }
        if (candidates.stream().map(ConceptKey::value).noneMatch(answer.selected()::equals)) {
            throw new ProviderProtocolException("Primary topic " + answer.selected() + " was not offered");
        }
        var ranked = new ArrayList<>(answer.probabilities().values());
        ranked.sort(Comparator.reverseOrder());
        var best = answer.probabilities().getOrDefault(answer.selected(), 0.0);
        var margin = ranked.size() < 2 ? 1.0 : ranked.get(0) - ranked.get(1);
        if (best < properties.primaryThreshold() || margin < properties.primaryMargin()) {
            return null;
        }
        return ConceptKey.of(answer.selected());
    }

    private static DecisionSpec topicsSpec(ConceptRegistry registry) {
        var questions = new LinkedHashMap<String, SemanticQuestion>();
        for (var leaf : registry.leaves()) {
            questions.put(questionName(leaf.key()), new YesNoQuestion(
                    "Is this a main subject of the section: " + leaf.label() + "?",
                    leaf.definition(),
                    "The section does not deal with " + leaf.label().toLowerCase()
                            + ", or mentions it only in passing."));
        }
        return new DecisionSpec(TOPICS_SPEC, registry.version(),
                "Decide which legal topics a contract or playbook section expresses.",
                SectionState.SCHEMA_VERSION, new QuestionPack(questions));
    }

    static String questionName(ConceptKey key) {
        return QUESTION_PREFIX + key.value().toLowerCase();
    }

    private String truncate(String text) {
        return text == null || text.length() <= properties.textLimit()
                ? text : text.substring(0, properties.textLimit());
    }

    /** The slice of a section a topic decision needs: its heading and its text. */
    record SectionState(String heading, String text) implements SemanticState {

        static final int SCHEMA_VERSION = 1;

        @Override
        public int schemaVersion() {
            return SCHEMA_VERSION;
        }

        @Override
        public Object payload() {
            var payload = new LinkedHashMap<String, Object>();
            if (heading != null && !heading.isBlank()) {
                payload.put("heading", heading);
            }
            payload.put("text", text == null ? "" : text);
            return payload;
        }
    }
}
