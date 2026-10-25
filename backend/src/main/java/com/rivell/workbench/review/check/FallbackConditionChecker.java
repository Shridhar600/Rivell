package com.rivell.workbench.review.check;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import com.rivell.workbench.intelligence.GenerativeReasoningService;
import com.rivell.workbench.intelligence.ProviderFailures;
import com.rivell.workbench.intelligence.ProviderProtocolException;
import com.rivell.workbench.review.FallbackCondition;
import com.rivell.workbench.review.FallbackConditionSpecs;

/**
 * Severity R3 lever P3: asks whether a clause judged "within a listed fallback" meets that fallback's
 * conditions, one by one (see {@link FallbackConditionSpecs}). Code owns the rule: a condition is
 * kept only when its source words are in the position text, and MET or UNMET only with a quote the
 * clause or the contract facts hold. A fallback is PARTIAL when no listed fallback has all its
 * conditions MET and a grounded condition is NOT_STATED or UNMET. The check reports; it never changes
 * the category and every failure becomes an outcome the executor keeps as "unchanged".
 */
final class FallbackConditionChecker {

    /** A condition's words are model-written and reach a user-visible note: cut them. */
    private static final int MAX_CONDITION_TEXT = 160;
    private static final Pattern LINE = Pattern.compile(
            "^\\s*(\\d+)\\s*\\|\\s*(MET|UNMET|UNKNOWN)\\s*\\|(.*)\\|([^|]+)\\|([^|]+?)\\s*$",
            Pattern.CASE_INSENSITIVE);

    /**
     * {@code listed}: TRUE when the position has fallback text, FALSE when it is structured and has
     * none, null for a prose position (the model says whether it lists a fallback).
     * {@code fallbackCount}: how many fallbacks a structured position lists, null for a prose position.
     */
    record Ask(String ourSide, String contractType, String heading, String clause, String position,
            Boolean listed, Integer fallbackCount, List<String> facts) {
    }

    /**
     * {@code outcome}: PARTIAL (a grounded condition is not stated or unmet and no listed fallback
     * has every condition met) or WITHIN; UNAVAILABLE, INVALID or OVER_BUDGET when the check did not
     * answer, and then there are no conditions. {@code executionId} links the generation when one ran.
     */
    record Result(String outcome, boolean fallbackListed, List<FallbackCondition> conditions,
            UUID executionId) {

        static final String PARTIAL = FallbackConditionSpecs.PARTIAL;
        static final String WITHIN = "WITHIN";
        static final String UNAVAILABLE = "UNAVAILABLE";
        static final String INVALID = "INVALID";
        static final String OVER_BUDGET = "OVER_BUDGET";

        Result {
            conditions = conditions == null ? List.of() : List.copyOf(conditions);
        }

        static Result failed(String outcome) {
            return new Result(outcome, false, List.of(), null);
        }

        boolean answered() {
            return PARTIAL.equals(outcome) || WITHIN.equals(outcome);
        }
    }

    /**
     * Whether the position lists a fallback: TRUE with fallback text, FALSE when it has role-tagged
     * fields but no fallback, null for a prose position (no role-tagged field), which the model reads.
     */
    static Boolean listed(com.rivell.workbench.retrieval.EvidenceItem.PolicyFacts facts) {
        if (facts == null) {
            return null;
        }
        if (!facts.fallbacks().isEmpty()) {
            return Boolean.TRUE;
        }
        return facts.preferred() != null || facts.prohibited() != null ? Boolean.FALSE : null;
    }

    private final GenerativeReasoningService generation;

    FallbackConditionChecker(GenerativeReasoningService generation) {
        this.generation = generation;
    }

    Result check(Ask ask) {
        try {
            var execution = generation.generate(FallbackConditionSpecs.spec(),
                    new FallbackConditionSpecs.FallbackConditionState(FallbackConditionSpecs.STATE_SCHEMA,
                            ask.ourSide(), ask.contractType(), ask.heading(), ask.clause(),
                            ask.position(), ask.facts()));
            var listed = ask.listed() != null ? ask.listed()
                    : FallbackConditionSpecs.YES.equals(
                            execution.fields().get(FallbackConditionSpecs.FALLBACK_LISTED));
            var conditions = parse(execution.fields().get(FallbackConditionSpecs.CONDITIONS), ask);
            if (conditions.isEmpty()) {
                return new Result(Result.INVALID, listed, List.of(), execution.executionId());
            }
            var grouped = listed ? conditions : asSingleGroup(conditions);
            return new Result(outcome(grouped), listed, grouped, execution.executionId());
        } catch (ProviderProtocolException e) {
            return Result.failed(Result.INVALID);
        } catch (RuntimeException e) {
            ProviderFailures.rethrowIfOutOfCredit(e);
            return Result.failed(Result.UNAVAILABLE);
        }
    }

    /**
     * A condition is kept only when the fallback words it came from are in the position text. An
     * UNKNOWN answer with those words is NOT_STATED; a MET or UNMET answer stands only with a quote
     * the clause or the facts hold, and otherwise becomes UNKNOWN.
     */
    private static List<FallbackCondition> parse(String lines, Ask ask) {
        if (lines == null) {
            return List.of();
        }
        var haystack = ask.clause() + "\n" + String.join("\n", ask.facts());
        var conditions = new ArrayList<FallbackCondition>();
        for (var line : lines.split("\\R")) {
            var matcher = LINE.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            var fallback = Integer.parseInt(matcher.group(1));
            if (ask.fallbackCount() != null && (fallback < 1 || fallback > ask.fallbackCount())) {
                continue;
            }
            if (!LlmAdjudicator.quoted(matcher.group(5).strip(), ask.position())) {
                continue;
            }
            var status = matcher.group(2).toUpperCase(Locale.ROOT);
            var quote = matcher.group(3).strip();
            var quoted = !FallbackConditionSpecs.UNKNOWN.equals(status) && LlmAdjudicator.quoted(quote, haystack);
            conditions.add(new FallbackCondition(fallback, capped(matcher.group(4).strip()),
                    FallbackConditionSpecs.UNKNOWN.equals(status) ? FallbackConditionSpecs.NOT_STATED
                            : quoted ? status : FallbackConditionSpecs.UNKNOWN,
                    quoted ? quote : null));
        }
        return conditions;
    }

    /** With no fallback listed there is one condition group, the preferred position, whatever the model numbered it. */
    private static List<FallbackCondition> asSingleGroup(List<FallbackCondition> conditions) {
        return conditions.stream()
                .map(condition -> new FallbackCondition(1, condition.condition(), condition.status(),
                        condition.quote()))
                .toList();
    }

    /**
     * PARTIAL when no listed fallback has all its conditions MET and at least one grounded condition
     * is NOT_STATED or UNMET; WITHIN otherwise. An UNKNOWN condition never makes a fallback partial.
     */
    private static String outcome(List<FallbackCondition> conditions) {
        var shortfall = conditions.stream().anyMatch(condition ->
                FallbackConditionSpecs.NOT_STATED.equals(condition.status())
                        || FallbackConditionSpecs.UNMET.equals(condition.status()));
        if (!shortfall) {
            return Result.WITHIN;
        }
        var fullyMet = new LinkedHashMap<Integer, Boolean>();
        for (var condition : conditions) {
            fullyMet.merge(condition.fallback(), FallbackConditionSpecs.MET.equals(condition.status()),
                    Boolean::logicalAnd);
        }
        return fullyMet.values().stream().anyMatch(Boolean::booleanValue)
                ? Result.WITHIN : Result.PARTIAL;
    }

    private static String capped(String text) {
        return text.length() <= MAX_CONDITION_TEXT ? text : text.substring(0, MAX_CONDITION_TEXT);
    }
}
