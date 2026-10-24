package com.rivell.workbench.review.check;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import com.rivell.workbench.intelligence.GenerativeReasoningService;
import com.rivell.workbench.intelligence.ProviderProtocolException;
import com.rivell.workbench.review.FallbackConditionSpecs;

/**
 * Severity R3 lever P3: asks whether a clause judged "within a listed fallback" meets that fallback's
 * conditions, one by one (see {@link FallbackConditionSpecs}). Code owns the rule: a condition counts
 * as UNMET or MET only with a quote the clause or the contract facts hold; a listed fallback fails
 * on one UNMET; the clause leaves WITHIN_FALLBACK only when every listed fallback fails. UNKNOWN never
 * raises anything. Every failure becomes an outcome the executor keeps as "unchanged": the check never
 * throws and never becomes a finding by itself.
 */
final class FallbackConditionChecker {

    /** A condition's words are model-written and reach a user-visible note: cut them. */
    private static final int MAX_CONDITION_TEXT = 160;
    private static final Pattern LINE = Pattern.compile(
            "^\\s*(\\d+)\\s*\\|\\s*(MET|UNMET|UNKNOWN)\\s*\\|(.*)\\|([^|]+?)\\s*$", Pattern.CASE_INSENSITIVE);

    /**
     * {@code listed}: TRUE when the position has fallback text, FALSE when it is structured and has
     * none, null for a prose position (the model says whether it lists a fallback).
     * {@code fallbackCount}: how many fallbacks a structured position lists, null for a prose position.
     */
    record Ask(String ourSide, String contractType, String heading, String clause, String position,
            Boolean listed, Integer fallbackCount, List<String> facts) {
    }

    /** {@code fallback}: the fallback number the condition belongs to; {@code quote} is null unless the status holds one. */
    record Condition(int fallback, String text, String status, String quote) {
    }

    /**
     * {@code outcome}: BEYOND (a quoted unmet condition fails every listed fallback) or WITHIN when the
     * check answered; UNAVAILABLE, INVALID or OVER_BUDGET when it did not, and then there are no
     * conditions. {@code executionId} links the generation when one ran.
     */
    record Result(String outcome, boolean fallbackListed, List<Condition> conditions, UUID executionId) {

        static final String BEYOND = "BEYOND";
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

        boolean raises() {
            return BEYOND.equals(outcome);
        }

        boolean answered() {
            return BEYOND.equals(outcome) || WITHIN.equals(outcome);
        }

        /** The conditions of fallbacks that still hold which could not be shown either way. */
        List<Condition> unknown() {
            if (!WITHIN.equals(outcome)) {
                return List.of();
            }
            var failed = conditions.stream()
                    .filter(condition -> FallbackConditionSpecs.UNMET.equals(condition.status()))
                    .map(Condition::fallback).collect(java.util.stream.Collectors.toSet());
            return conditions.stream()
                    .filter(condition -> FallbackConditionSpecs.UNKNOWN.equals(condition.status())
                            && !failed.contains(condition.fallback()))
                    .toList();
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
            return new Result(raises(grouped, listed ? ask.fallbackCount() : Integer.valueOf(1))
                    ? Result.BEYOND : Result.WITHIN, listed, grouped, execution.executionId());
        } catch (ProviderProtocolException e) {
            return Result.failed(Result.INVALID);
        } catch (RuntimeException e) {
            if (e instanceof com.rivell.workbench.intelligence.ProviderOutOfCreditException) {
                throw e;
            }
            return Result.failed(Result.UNAVAILABLE);
        }
    }

    /** MET and UNMET stand only with a quote the clause or the facts hold; otherwise the condition is UNKNOWN. */
    private static List<Condition> parse(String lines, Ask ask) {
        if (lines == null) {
            return List.of();
        }
        var haystack = ask.clause() + "\n" + String.join("\n", ask.facts());
        var conditions = new ArrayList<Condition>();
        for (var line : lines.split("\\R")) {
            var matcher = LINE.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            var status = matcher.group(2).toUpperCase(Locale.ROOT);
            var quote = matcher.group(3).strip();
            var quoted = !FallbackConditionSpecs.UNKNOWN.equals(status) && LlmAdjudicator.quoted(quote, haystack);
            conditions.add(new Condition(Integer.parseInt(matcher.group(1)), capped(matcher.group(4).strip()),
                    FallbackConditionSpecs.UNKNOWN.equals(status) || !quoted
                            ? FallbackConditionSpecs.UNKNOWN : status,
                    quoted ? quote : null));
        }
        return conditions;
    }

    /** With no fallback listed there is one condition group, the preferred position, whatever the model numbered it. */
    private static List<Condition> asSingleGroup(List<Condition> conditions) {
        return conditions.stream()
                .map(condition -> new Condition(1, condition.text(), condition.status(), condition.quote()))
                .toList();
    }

    /**
     * Every listed fallback must have failed: numbers 1..{@code expected} each need a quoted UNMET. A
     * fallback the model did not report is not a failed fallback; numbers outside the range are ignored.
     * For a prose position ({@code expected} null) the reported numbers must run from 1 without gaps.
     */
    private static boolean raises(List<Condition> conditions, Integer expected) {
        var failed = new LinkedHashMap<Integer, Boolean>();
        for (var condition : conditions) {
            failed.merge(condition.fallback(), FallbackConditionSpecs.UNMET.equals(condition.status()),
                    Boolean::logicalOr);
        }
        if (expected == null) {
            var numbers = failed.keySet().stream().sorted().toList();
            for (var index = 0; index < numbers.size(); index++) {
                if (numbers.get(index) != index + 1) {
                    return false;
                }
            }
            return !failed.isEmpty() && failed.values().stream().allMatch(Boolean::booleanValue);
        }
        if (expected < 1) {
            return false;
        }
        for (var number = 1; number <= expected; number++) {
            if (!Boolean.TRUE.equals(failed.get(number))) {
                return false;
            }
        }
        return true;
    }

    private static String capped(String text) {
        return text.length() <= MAX_CONDITION_TEXT ? text : text.substring(0, MAX_CONDITION_TEXT);
    }
}
