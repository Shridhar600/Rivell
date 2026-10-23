package com.rivell.workbench.review.check;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.rivell.workbench.review.PolicyComparisonSpecs;
import com.rivell.workbench.review.ReviewEngineProperties;

class DecisionRouterTest {

    private static final ReviewEngineProperties.Routing BAND =
            new ReviewEngineProperties.Routing(0.4, 0.8, 40, -1.0);

    /** A judgement with this flag mass; it flags at the product threshold 0.6. */
    private static PolicyComparisonOutcome.Judgement jev(double flagMass) {
        var flagged = flagMass >= 0.6;
        return new PolicyComparisonOutcome.Judgement(
                flagged ? PolicyComparisonSpecs.BEYOND_FALLBACK : null, flagMass, flagged, Map.of());
    }

    @Test
    void onlyAFlagMassInsideTheDoubtBandGoesToASecondOpinion() {
        assertThat(DecisionRouter.route(jev(0.39), true, BAND)).isEqualTo(DecisionRouter.Route.ACCEPT);
        assertThat(DecisionRouter.route(jev(0.40), true, BAND)).isEqualTo(DecisionRouter.Route.ADJUDICATE);
        assertThat(DecisionRouter.route(jev(0.79), true, BAND)).isEqualTo(DecisionRouter.Route.ADJUDICATE);
        assertThat(DecisionRouter.route(jev(0.80), true, BAND)).isEqualTo(DecisionRouter.Route.ACCEPT);
    }

    @Test
    void aPositionThatDoesNotAddressTheClauseIsLeftToTheTriage() {
        assertThat(DecisionRouter.route(jev(0.45), false, BAND)).isEqualTo(DecisionRouter.Route.ACCEPT);
    }

    @Test
    void agreementIsOnWhetherToFlagNotOnTheCategory() {
        assertThat(DecisionRouter.agrees(jev(0.7), PolicyComparisonSpecs.WITHIN_FALLBACK)).isTrue();
        assertThat(DecisionRouter.agrees(jev(0.7), PolicyComparisonSpecs.MEETS_PREFERRED)).isFalse();
        assertThat(DecisionRouter.agrees(jev(0.5), PolicyComparisonSpecs.NOT_ADDRESSED)).isTrue();
        assertThat(DecisionRouter.agrees(jev(0.5), PolicyComparisonSpecs.UNCLEAR)).isFalse();
    }

    private static final ReviewEngineProperties.Routing WITH_SPLIT =
            new ReviewEngineProperties.Routing(0.4, 0.8, 40, 0.45);

    /** A confident WITHIN_FALLBACK judgement: flag mass 0.95, this much of it BEYOND + WALK_AWAY. */
    private static PolicyComparisonOutcome.Judgement within(double beyond, double walkAway) {
        var within = 0.95 - beyond - walkAway;
        return new PolicyComparisonOutcome.Judgement(PolicyComparisonSpecs.WITHIN_FALLBACK, 0.95, true,
                Map.of(PolicyComparisonSpecs.WITHIN_FALLBACK, within,
                        PolicyComparisonSpecs.BEYOND_FALLBACK, beyond,
                        PolicyComparisonSpecs.WALK_AWAY, walkAway,
                        PolicyComparisonSpecs.MEETS_PREFERRED, 0.05));
    }

    @Test
    void p2AConfidentWithinWhoseWorseMassReachesTheMarginGoesToASecondOpinion() {
        var split = within(0.34, 0.14);

        assertThat(DecisionRouter.route(split, true, WITH_SPLIT)).isEqualTo(DecisionRouter.Route.ADJUDICATE);
        assertThat(DecisionRouter.routedByWithinSplit(split, true, WITH_SPLIT)).isTrue();
    }

    @Test
    void p2AWithinBelowTheMarginStaysAccepted() {
        var split = within(0.30, 0.14);

        assertThat(DecisionRouter.route(split, true, WITH_SPLIT)).isEqualTo(DecisionRouter.Route.ACCEPT);
        assertThat(DecisionRouter.routedByWithinSplit(split, true, WITH_SPLIT)).isFalse();
    }

    @Test
    void p2ANegativeMarginTurnsItOffSoOnlyTheBandRoutes() {
        var split = within(0.34, 0.14);
        var off = new ReviewEngineProperties.Routing(0.4, 0.8, 40, -1.0);

        assertThat(DecisionRouter.route(split, true, off)).isEqualTo(DecisionRouter.Route.ACCEPT);
        assertThat(DecisionRouter.routedByWithinSplit(split, true, off)).isFalse();
    }

    @Test
    void p2OnlyAWithinJudgementIsRoutedBySplitAndAPositionThatDoesNotAddressIsNot() {
        var beyond = new PolicyComparisonOutcome.Judgement(PolicyComparisonSpecs.BEYOND_FALLBACK, 0.95, true,
                Map.of(PolicyComparisonSpecs.BEYOND_FALLBACK, 0.6, PolicyComparisonSpecs.WALK_AWAY, 0.35));

        assertThat(DecisionRouter.route(beyond, true, WITH_SPLIT)).isEqualTo(DecisionRouter.Route.ACCEPT);
        assertThat(DecisionRouter.route(within(0.34, 0.14), false, WITH_SPLIT))
                .isEqualTo(DecisionRouter.Route.ACCEPT);
    }

    @Test
    void p2AJudgementAlreadyInTheBandIsNotTaggedAsRoutedBySplit() {
        var inBand = new PolicyComparisonOutcome.Judgement(PolicyComparisonSpecs.WITHIN_FALLBACK, 0.7, true,
                Map.of(PolicyComparisonSpecs.WITHIN_FALLBACK, 0.2, PolicyComparisonSpecs.BEYOND_FALLBACK, 0.3,
                        PolicyComparisonSpecs.WALK_AWAY, 0.2));

        assertThat(DecisionRouter.route(inBand, true, WITH_SPLIT)).isEqualTo(DecisionRouter.Route.ADJUDICATE);
        assertThat(DecisionRouter.routedByWithinSplit(inBand, true, WITH_SPLIT)).isFalse();
    }
}
