package com.rivell.workbench.review;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Engine v2 settings (spec §5, §6). {@code omissions}: the engine v2 omissions lane
 * (OMISSION plan check); off by default after the P4 eval. {@code definitions}: engine v2 judges
 * read the definitions of the terms a clause uses (P3); off by default after the P3 eval (rule R1).
 * {@code routing}: a judged position
 * that addresses the clause gets a second opinion when its flag mass is in [adjudicateFrom,
 * adjudicateBelow) — P0 measured 0.4 to 0.8, about 4.4 per contract on dev and dev2 — and at
 * most {@code maxAdjudications} second opinions run per analysis run; the rest go to the user.
 *
 * <p>Severity R3 experiment (P7), each lever behind its own setting and tagged on the findings it
 * changes: {@code severity.agreedWorseCategory} (P1: an agreed second opinion lends its category
 * when it is worse), {@code routing.withinSplitMargin} (P2: a WITHIN_FALLBACK judgement whose
 * BEYOND and WALK_AWAY mass reaches the margin also gets a second opinion; negative turns it off),
 * {@code fallbackConditions} (P3: a condition-by-condition check of every final WITHIN_FALLBACK;
 * on by default, it reports the conditions on the finding and never changes the category).
 *
 * <p>{@code walkAwayAbsence}: one check reports a published position whose walk-away
 * describes an absent right or protection the contract does not meet, whether no clause
 * addresses it or a clause grants it; on by default, and never on a contract type made under a
 * parent agreement.
 *
 * <p>{@code maxTransientFailures}: how many transient infrastructure failures one analysis run may
 * survive before it is failed terminally (default 3; 0 makes every transient failure terminal).
 */
@ConfigurationProperties("rivell.review")
public record ReviewEngineProperties(
        Routing routing, Boolean omissions, Boolean definitions, Severity severity,
        FallbackConditions fallbackConditions, WalkAwayAbsence walkAwayAbsence,
        Duration analysisBudget, Integer maxTransientFailures) {

    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public ReviewEngineProperties {
        routing = routing == null ? new Routing(null, null, null, null) : routing;
        omissions = omissions == null ? Boolean.FALSE : omissions;
        definitions = definitions == null ? Boolean.FALSE : definitions;
        severity = severity == null ? new Severity(null) : severity;
        fallbackConditions = fallbackConditions == null
                ? new FallbackConditions(null, null) : fallbackConditions;
        walkAwayAbsence = walkAwayAbsence == null
                ? new WalkAwayAbsence(null) : walkAwayAbsence;
        analysisBudget = analysisBudget == null ? Duration.ofMinutes(30) : analysisBudget;
        if (analysisBudget.isZero() || analysisBudget.isNegative()) {
            throw new IllegalArgumentException("rivell.review.analysis-budget must be positive");
        }
        maxTransientFailures = maxTransientFailures == null ? 3 : maxTransientFailures;
        if (maxTransientFailures < 0) {
            throw new IllegalArgumentException(
                    "rivell.review.max-transient-failures must be >= 0");
        }
    }

    public ReviewEngineProperties(Routing routing, Boolean omissions, Boolean definitions,
            Severity severity, FallbackConditions fallbackConditions,
            Duration analysisBudget, Integer maxTransientFailures) {
        this(routing, omissions, definitions, severity, fallbackConditions, null,
                analysisBudget, maxTransientFailures);
    }

    public ReviewEngineProperties(Routing routing, Boolean omissions, Boolean definitions,
            Severity severity, FallbackConditions fallbackConditions,
            Duration analysisBudget) {
        this(routing, omissions, definitions, severity, fallbackConditions, null,
                analysisBudget, null);
    }

    public ReviewEngineProperties(Routing routing, Boolean omissions, Boolean definitions,
            Severity severity, FallbackConditions fallbackConditions) {
        this(routing, omissions, definitions, severity, fallbackConditions, null, null, null);
    }

    /** P1: on an agreed second opinion the finding takes the worse of the two categories. */
    public record Severity(Boolean agreedWorseCategory) {

        public Severity {
            agreedWorseCategory = agreedWorseCategory == null ? Boolean.TRUE : agreedWorseCategory;
        }
    }

    /**
     * P3: the condition-by-condition check; at most {@code maxChecks} calls per analysis run. On by
     * default: it reports the fallback's conditions on the finding and never changes the category.
     */
    public record FallbackConditions(Boolean enabled, Integer maxChecks) {

        public FallbackConditions {
            enabled = enabled == null ? Boolean.TRUE : enabled;
            maxChecks = maxChecks == null ? 40 : maxChecks;
            if (maxChecks < 0) {
                throw new IllegalArgumentException(
                        "rivell.review.fallback-conditions.max-checks must be >= 0");
            }
        }
    }

    /**
     * The walk-away absence check: report a walk-away that describes an absent right when no
     * clause addresses the position. On by default; the omission lane's own trigger stays behind
     * {@code omissions}.
     */
    public record WalkAwayAbsence(Boolean enabled) {

        public WalkAwayAbsence {
            enabled = enabled == null ? Boolean.TRUE : enabled;
        }
    }

    public record Routing(Double adjudicateFrom, Double adjudicateBelow, Integer maxAdjudications,
            Double withinSplitMargin) {

        public Routing {
            withinSplitMargin = withinSplitMargin == null ? 0.45 : withinSplitMargin;
            if (withinSplitMargin > 1.0) {
                throw new IllegalArgumentException(
                        "rivell.review.routing.within-split-margin must be <= 1 (negative turns it off)");
            }
            adjudicateFrom = adjudicateFrom == null ? 0.4 : adjudicateFrom;
            adjudicateBelow = adjudicateBelow == null ? 0.8 : adjudicateBelow;
            maxAdjudications = maxAdjudications == null ? 40 : maxAdjudications;
            if (adjudicateFrom < 0.0 || adjudicateBelow > 1.0 || adjudicateFrom >= adjudicateBelow) {
                throw new IllegalArgumentException(
                        "rivell.review.routing band must satisfy 0 <= adjudicate-from < adjudicate-below <= 1");
            }
            if (maxAdjudications < 0) {
                throw new IllegalArgumentException("rivell.review.routing.max-adjudications must be >= 0");
            }
        }

        /** P2 is on when the margin is not negative. */
        public boolean withinSplitEnabled() {
            return withinSplitMargin >= 0.0;
        }
    }
}
