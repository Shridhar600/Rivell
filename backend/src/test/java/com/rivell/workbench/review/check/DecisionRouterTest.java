package com.rivell.workbench.review.check;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.rivell.workbench.review.PolicyComparisonSpecs;
import com.rivell.workbench.review.ReviewEngineProperties;

class DecisionRouterTest {

    private static final ReviewEngineProperties.Routing BAND =
            new ReviewEngineProperties.Routing(0.4, 0.8, 40);

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
}
