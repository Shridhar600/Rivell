package com.rivell.workbench.review.check;

import com.rivell.workbench.review.PolicyComparisonSpecs;
import com.rivell.workbench.review.ReviewEngineProperties;

/**
 * Engine v2 routing (spec §6): which judged positions get a second opinion. Pure: no calls, no
 * state. A position that does not address the clause stays with the uncovered-clause triage.
 * A position that addresses it gets a second opinion when Jev's flag mass is in the doubt band;
 * outside the band, Jev's answer stands. P2 (severity R3): a WITHIN_FALLBACK judgement whose
 * BEYOND_FALLBACK + WALK_AWAY mass reaches the within-split margin also gets one, whatever its flag
 * mass, because a confident "within" can hide a worse category (a second look decides, not arithmetic).
 */
final class DecisionRouter {

    enum Route { ACCEPT, ADJUDICATE }

    private DecisionRouter() {
    }

    static Route route(PolicyComparisonOutcome.Judgement jev, boolean addresses,
            ReviewEngineProperties.Routing routing) {
        return inBand(jev, addresses, routing) || withinSplit(jev, addresses, routing)
                ? Route.ADJUDICATE : Route.ACCEPT;
    }

    /** P2 routed this position: the split sent it, and the doubt band would not have. */
    static boolean routedByWithinSplit(PolicyComparisonOutcome.Judgement jev, boolean addresses,
            ReviewEngineProperties.Routing routing) {
        return withinSplit(jev, addresses, routing) && !inBand(jev, addresses, routing);
    }

    private static boolean inBand(PolicyComparisonOutcome.Judgement jev, boolean addresses,
            ReviewEngineProperties.Routing routing) {
        var mass = jev.flagProbability();
        return addresses && mass >= routing.adjudicateFrom() && mass < routing.adjudicateBelow();
    }

    private static boolean withinSplit(PolicyComparisonOutcome.Judgement jev, boolean addresses,
            ReviewEngineProperties.Routing routing) {
        if (!addresses || !routing.withinSplitEnabled()
                || !PolicyComparisonSpecs.WITHIN_FALLBACK.equals(jev.category())) {
            return false;
        }
        var worse = jev.probabilities().getOrDefault(PolicyComparisonSpecs.BEYOND_FALLBACK, 0.0)
                + jev.probabilities().getOrDefault(PolicyComparisonSpecs.WALK_AWAY, 0.0);
        return worse >= routing.withinSplitMargin();
    }

    /** Agreement is on the flag decision: both flag, or neither does (plan decision D2). */
    static boolean agrees(PolicyComparisonOutcome.Judgement jev, String secondOpinionCategory) {
        return jev.flagged() == PolicyComparisonSpecs.FLAGGING.contains(secondOpinionCategory);
    }
}
