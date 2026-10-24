package com.rivell.workbench.review.check;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.rivell.workbench.intelligence.GenerativeReasoningService;
import com.rivell.workbench.intelligence.ProviderProtocolException;
import com.rivell.workbench.review.AdjudicationSpecs;
import com.rivell.workbench.review.PolicyComparisonSpecs;

/**
 * Asks the second opinion on one judged position Jev was unsure about (spec §6, §7). Offers only
 * Jev's plausible options, requires a quote the clause holds, and turns every failure into an
 * outcome the executor routes to the user: a second opinion never fails silently and never becomes
 * a finding by itself.
 */
final class LlmAdjudicator {

    /** An option Jev gave at least this probability is plausible (plan decision D4). */
    static final double PLAUSIBLE = 0.05;
    /** Shorter than this ("the", "shall not"), a quote cannot show what the answer rests on. */
    static final int MIN_QUOTE_LENGTH = 12;

    record Ask(String ourSide, String contractType, String heading, String clause, String position,
            List<String> definitions) {
    }

    /**
     * {@code outcome}: AGREED or DISAGREED when the second opinion answered; UNAVAILABLE, INVALID or
     * OVER_BUDGET when it did not, and then {@code category}, {@code quote} and {@code reason} are
     * null. {@code executionId} links the generation when one ran.
     */
    record SecondOpinion(String outcome, String category, String quote, String reason, UUID executionId) {

        static final String AGREED = "AGREED";
        static final String DISAGREED = "DISAGREED";
        static final String UNAVAILABLE = "UNAVAILABLE";
        static final String INVALID = "INVALID";
        static final String OVER_BUDGET = "OVER_BUDGET";

        static SecondOpinion failed(String outcome) {
            return new SecondOpinion(outcome, null, null, null, null);
        }

        boolean agreed() {
            return AGREED.equals(outcome);
        }

        boolean answered() {
            return AGREED.equals(outcome) || DISAGREED.equals(outcome);
        }
    }

    private final GenerativeReasoningService generation;

    LlmAdjudicator(GenerativeReasoningService generation) {
        this.generation = generation;
    }

    SecondOpinion ask(Ask ask, PolicyComparisonOutcome.Judgement jev) {
        var options = new LinkedHashMap<String, String>();
        plausibleOptions(jev.probabilities())
                .forEach(key -> options.put(key, PolicyComparisonSpecs.OPTIONS.get(key)));
        try {
            var execution = generation.generate(AdjudicationSpecs.secondOpinion(options.keySet()),
                    new AdjudicationSpecs.SecondOpinionState(AdjudicationSpecs.STATE_SCHEMA,
                            ask.ourSide(), ask.contractType(), ask.heading(), ask.clause(),
                            ask.position(), options, ask.definitions()));
            var category = execution.fields().get(AdjudicationSpecs.CATEGORY);
            var quote = execution.fields().get(AdjudicationSpecs.QUOTE);
            if (!quoted(quote, ask.clause())) {
                return new SecondOpinion(SecondOpinion.INVALID, null, null, null, execution.executionId());
            }
            return new SecondOpinion(
                    DecisionRouter.agrees(jev, category) ? SecondOpinion.AGREED : SecondOpinion.DISAGREED,
                    category, quote.strip(), execution.fields().get(AdjudicationSpecs.REASON),
                    execution.executionId());
        } catch (ProviderProtocolException e) {
            return SecondOpinion.failed(SecondOpinion.INVALID);
        } catch (RuntimeException e) {
            if (e instanceof com.rivell.workbench.intelligence.ProviderOutOfCreditException) {
                throw e;
            }
            return SecondOpinion.failed(SecondOpinion.UNAVAILABLE);
        }
    }

    /** Options Jev gave at least {@link #PLAUSIBLE}, plus its top two always, in Jev's option order. */
    static List<String> plausibleOptions(Map<String, Double> probabilities) {
        var ranked = new ArrayList<>(PolicyComparisonSpecs.OPTIONS.keySet());
        ranked.sort(Comparator.comparingDouble(
                (String key) -> probabilities.getOrDefault(key, 0.0)).reversed());
        var topTwo = ranked.subList(0, 2);
        return PolicyComparisonSpecs.OPTIONS.keySet().stream()
                .filter(key -> topTwo.contains(key) || probabilities.getOrDefault(key, 0.0) >= PLAUSIBLE)
                .toList();
    }

    /**
     * The clause holds the quote, ignoring case, spacing, quote-mark style and a final full stop, and
     * the quote is long enough to show what the answer rests on.
     */
    static boolean quoted(String quote, String clause) {
        if (quote == null || clause == null) {
            return false;
        }
        var wanted = normalize(quote).replaceAll("^[\"'\\s]+|[\"'.\\s]+$", "");
        return wanted.length() >= MIN_QUOTE_LENGTH && normalize(clause).contains(wanted);
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replaceAll("[\u2018\u2019]", "'")
                .replaceAll("[\u201C\u201D]", "\"")
                .replaceAll("\\s+", " ")
                .strip()
                .toLowerCase(Locale.ROOT);
    }
}
