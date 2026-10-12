package com.rivell.workbench.review.check;

import com.rivell.workbench.review.PolicyComparisonSpecs;
import com.rivell.workbench.review.ReviewEngineProperties;

/**
 * Engine v2 routing (spec §6): which judged positions get a second opinion. Pure: no calls, no
 * state. A position that does not address the clause stays with the uncovered-clause triage, as in
 * v1. A position that addresses it gets a second opinion when Jev's flag mass is in the doubt band;
 * outside the band, Jev's answer stands.
 */
final class DecisionRouter {

    enum Route { ACCEPT, ADJUDICATE }

    private DecisionRouter() {
    }

    static Route route(PolicyComparisonOutcome.Judgement jev, boolean addresses,
            ReviewEngineProperties.Routing routing) {
        var mass = jev.flagProbability();
        return addresses && mass >= routing.adjudicateFrom() && mass < routing.adjudicateBelow()
                ? Route.ADJUDICATE : Route.ACCEPT;
    }

    /** Agreement is on the flag decision: both flag, or neither does (plan decision D2). */
    static boolean agrees(PolicyComparisonOutcome.Judgement jev, String secondOpinionCategory) {
        return jev.flagged() == PolicyComparisonSpecs.FLAGGING.contains(secondOpinionCategory);
    }
}
