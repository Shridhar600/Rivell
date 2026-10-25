package com.rivell.workbench.review.check;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.rivell.workbench.intelligence.GenerationRequest;
import com.rivell.workbench.intelligence.GenerationResult;
import com.rivell.workbench.intelligence.GenerativeProviderRegistry;
import com.rivell.workbench.intelligence.GenerativeReasoningProvider;
import com.rivell.workbench.intelligence.GenerativeReasoningService;
import com.rivell.workbench.intelligence.ModelBinding;
import com.rivell.workbench.intelligence.ProviderOutOfCreditException;
import com.rivell.workbench.intelligence.ProviderUnavailableException;
import com.rivell.workbench.intelligence.TokenUsage;
import com.rivell.workbench.review.FallbackCondition;
import com.rivell.workbench.review.FallbackConditionSpecs;

class FallbackConditionCheckerTest {

    private static final String CLAUSE = "This Agreement is governed by the laws of Switzerland. "
            + "The courts of Zurich have exclusive jurisdiction.";
    private static final String POSITION = "Compare the clause with our playbook position on Governing Law.\n"
            + "Preferred: English law\nFallback 1: Swiss law if the counterparty is Swiss\nWalk-away: US law";
    private static final String SOURCE = "Swiss law if the counterparty is Swiss";
    private static final List<String> FACTS = List.of("Party: Helvetia Cloud AG (SUPPLIER)",
            "Party: Studio Ltd of London (CUSTOMER)", "Governing law (stated): Switzerland");

    private static final String CALDER_CLAUSE = "Either party may terminate this agreement for material "
            + "breach on thirty (30) days' notice if the breach is not remedied.";
    private static final String CALDER_POSITION = "Compare the clause with our playbook position on "
            + "Termination for breach.\nPreferred: Either party may terminate on ninety (90) days' notice.\n"
            + "Fallback 1: A thirty (30) day cure period, with a right for us to suspend performance "
            + "before termination\nWalk-away: No cure period.";
    private static final String CALDER_SOURCE = "A thirty (30) day cure period, with a right for us to "
            + "suspend performance before termination";

    private final List<GenerationRequest> requests = new ArrayList<>();

    private FallbackConditionChecker checkerAnswering(Function<GenerationRequest, Map<String, String>> answer) {
        GenerativeReasoningProvider provider = new GenerativeReasoningProvider() {
            @Override
            public String id() {
                return "fake";
            }

            @Override
            public GenerationResult generate(GenerationRequest request) {
                requests.add(request);
                return new GenerationResult(answer.apply(request), "fake-gen", new TokenUsage(10, 5));
            }
        };
        var service = new GenerativeReasoningService(
                asset -> new ModelBinding("fake", "alias"),
                (GenerativeProviderRegistry) id -> Optional.of(provider), Clock.systemUTC());
        return new FallbackConditionChecker(service);
    }

    private static FallbackConditionChecker.Ask ask(Boolean listed) {
        return ask(listed, Boolean.TRUE.equals(listed) ? 1 : null);
    }

    private static FallbackConditionChecker.Ask ask(Boolean listed, Integer fallbackCount) {
        return new FallbackConditionChecker.Ask("We are the customer.", "MSA", "Governing Law", CLAUSE,
                POSITION, listed, fallbackCount, FACTS);
    }

    private static FallbackConditionChecker.Ask calderAsk() {
        return new FallbackConditionChecker.Ask("We are the customer.", "MSA", "Termination for breach",
                CALDER_CLAUSE, CALDER_POSITION, true, 1, List.of("Our side: CUSTOMER"));
    }

    private static Map<String, String> answer(String listed, String... lines) {
        return Map.of(FallbackConditionSpecs.FALLBACK_LISTED, listed,
                FallbackConditionSpecs.CONDITIONS, String.join("\n", lines));
    }

    /** One line grounded in the position's own fallback words. */
    private static String condition(String line) {
        return condition(line, SOURCE);
    }

    private static String condition(String line, String source) {
        return line + " | " + source;
    }

    @Test
    void anUnmetConditionWithAQuoteTheFactsHoldMakesTheFallbackPartial() {
        var checker = checkerAnswering(request -> answer("YES",
                condition("1 | UNMET | Studio Ltd of London (CUSTOMER) | the counterparty is Swiss")));

        var result = checker.check(ask(true));

        assertThat(result.outcome()).isEqualTo(FallbackConditionChecker.Result.PARTIAL);
        assertThat(result.answered()).isTrue();
        assertThat(result.fallbackListed()).isTrue();
        assertThat(result.conditions()).singleElement().satisfies(condition -> {
            assertThat(condition.status()).isEqualTo(FallbackConditionSpecs.UNMET);
            assertThat(condition.quote()).isEqualTo("Studio Ltd of London (CUSTOMER)");
        });
        assertThat(result.executionId()).isNotNull();
    }

    @Test
    void anUnmetConditionWithAQuoteTheClauseHoldsAlsoMakesItPartial() {
        var checker = checkerAnswering(request -> answer("YES",
                condition("1 | UNMET | exclusive jurisdiction | the courts are not Swiss courts")));

        assertThat(checker.check(ask(true)).outcome()).isEqualTo(FallbackConditionChecker.Result.PARTIAL);
    }

    @Test
    void anUnmetConditionWhoseQuoteNeitherTheClauseNorTheFactsHoldStaysUnknownAndWithin() {
        var checker = checkerAnswering(request -> answer("YES",
                condition("1 | UNMET | the parties agree to arbitration in Paris | the forum is Swiss")));

        var result = checker.check(ask(true));

        assertThat(result.outcome()).isEqualTo(FallbackConditionChecker.Result.WITHIN);
        assertThat(result.conditions()).singleElement().satisfies(condition -> {
            assertThat(condition.status()).isEqualTo(FallbackConditionSpecs.UNKNOWN);
            assertThat(condition.quote()).isNull();
        });
    }

    @Test
    void aGroundedConditionTheClauseDoesNotShowIsNotStated() {
        var checker = checkerAnswering(request -> answer("YES",
                "1 | UNKNOWN | - | a right for us to suspend performance before termination | "
                        + CALDER_SOURCE));

        var result = checker.check(calderAsk());

        assertThat(result.outcome()).isEqualTo(FallbackConditionChecker.Result.PARTIAL);
        assertThat(result.conditions()).singleElement().satisfies(condition -> {
            assertThat(condition.status()).isEqualTo(FallbackConditionSpecs.NOT_STATED);
            assertThat(condition.condition())
                    .isEqualTo("a right for us to suspend performance before termination");
            assertThat(condition.quote()).isNull();
        });
    }

    @Test
    void oneOfTwoConditionsNotStatedMakesTheFallbackPartial() {
        var checker = checkerAnswering(request -> answer("YES",
                "1 | MET | thirty (30) days' notice | a thirty day cure period | " + CALDER_SOURCE,
                "1 | UNKNOWN | - | a right for us to suspend performance before termination | "
                        + CALDER_SOURCE));

        var result = checker.check(calderAsk());

        assertThat(result.outcome()).isEqualTo(FallbackConditionChecker.Result.PARTIAL);
        assertThat(result.conditions()).extracting(FallbackCondition::status)
                .containsExactly(FallbackConditionSpecs.MET, FallbackConditionSpecs.NOT_STATED);
    }

    @Test
    void anUngroundedConditionIsDropped() {
        var checker = checkerAnswering(request -> answer("YES",
                condition("1 | MET | laws of Switzerland | the law is Swiss"),
                condition("1 | UNKNOWN | - | an invented condition", "not in the fallback")));

        var result = checker.check(ask(true));

        assertThat(result.conditions()).singleElement().satisfies(condition ->
                assertThat(condition.status()).isEqualTo(FallbackConditionSpecs.MET));
        assertThat(result.outcome()).isEqualTo(FallbackConditionChecker.Result.WITHIN);
    }

    @Test
    void whenEveryConditionIsMetTheClauseStaysWithinWithNothingNotStated() {
        var checker = checkerAnswering(request -> answer("YES",
                condition("1 | MET | laws of Switzerland | the law is Swiss"),
                condition("1 | MET | Helvetia Cloud AG (SUPPLIER) | the counterparty is Swiss")));

        var result = checker.check(ask(true));

        assertThat(result.outcome()).isEqualTo(FallbackConditionChecker.Result.WITHIN);
        assertThat(result.conditions()).hasSize(2)
                .extracting(FallbackCondition::status)
                .containsOnly(FallbackConditionSpecs.MET);
    }

    @Test
    void aMetConditionWithoutAQuoteTheTextHoldsCountsAsUnknown() {
        var checker = checkerAnswering(request -> answer("YES",
                condition("1 | MET | the law is certainly Swiss | the law is Swiss")));

        assertThat(checker.check(ask(true)).conditions()).singleElement()
                .satisfies(condition -> assertThat(condition.status())
                        .isEqualTo(FallbackConditionSpecs.UNKNOWN));
    }

    @Test
    void severalFallbacksStayWithinWhenOneIsFullyMetAndArePartialOtherwise() {
        var oneStillHolds = checkerAnswering(request -> answer("YES",
                condition("1 | UNMET | exclusive jurisdiction | fallback one wants English courts"),
                condition("2 | MET | laws of Switzerland | fallback two wants Swiss law")));
        var allFail = checkerAnswering(request -> answer("YES",
                condition("1 | UNMET | exclusive jurisdiction | fallback one wants English courts"),
                condition("2 | UNMET | Studio Ltd of London (CUSTOMER) | fallback two wants a Swiss counterparty")));

        assertThat(oneStillHolds.check(ask(true, 2)).outcome())
                .isEqualTo(FallbackConditionChecker.Result.WITHIN);
        assertThat(allFail.check(ask(true, 2)).outcome())
                .isEqualTo(FallbackConditionChecker.Result.PARTIAL);
    }

    @Test
    void aPositionWithNoListedFallbackIsCheckedAgainstThePreferredPosition() {
        var fails = checkerAnswering(request -> answer("NO",
                condition("1 | UNMET | laws of Switzerland | the clause meets the preferred position")));
        var meets = checkerAnswering(request -> answer("NO",
                condition("1 | MET | laws of Switzerland | the clause meets the preferred position")));
        var unsure = checkerAnswering(request -> answer("NO",
                condition("1 | MET | the law is certainly Swiss | the clause meets the preferred position")));

        var shortfall = fails.check(ask(false));
        assertThat(shortfall.outcome()).isEqualTo(FallbackConditionChecker.Result.PARTIAL);
        assertThat(shortfall.fallbackListed()).isFalse();
        assertThat(meets.check(ask(false)).outcome()).isEqualTo(FallbackConditionChecker.Result.WITHIN);
        // an unverifiable quote stays UNKNOWN and never makes the preferred position partial
        assertThat(unsure.check(ask(false)).outcome()).isEqualTo(FallbackConditionChecker.Result.WITHIN);
    }

    @Test
    void forAProsePositionTheModelSaysWhetherAFallbackIsListed() {
        var checker = checkerAnswering(request -> answer("NO",
                condition("1 | UNMET | laws of Switzerland | the clause meets the preferred position")));

        var result = checker.check(ask(null));

        assertThat(result.fallbackListed()).isFalse();
        assertThat(result.outcome()).isEqualTo(FallbackConditionChecker.Result.PARTIAL);
    }

    @Test
    void theStateCarriesTheClauseThePositionAndTheContractFacts() {
        var checker = checkerAnswering(request -> answer("YES",
                condition("1 | MET | laws of Switzerland | the law is Swiss")));

        checker.check(ask(true));

        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request.spec().id()).isEqualTo(FallbackConditionSpecs.SPEC_ID);
            assertThat(request.spec().version()).isEqualTo(FallbackConditionSpecs.SPEC_VERSION);
            var state = (FallbackConditionSpecs.FallbackConditionState) request.state();
            assertThat(state.clause()).isEqualTo(CLAUSE);
            assertThat(state.position()).isEqualTo(POSITION);
            assertThat(state.facts()).isEqualTo(FACTS);
        });
    }

    @Test
    void aProviderFailureLeavesTheItemUnchangedAndNeverThrows() {
        GenerativeReasoningProvider down = new GenerativeReasoningProvider() {
            @Override
            public String id() {
                return "fake";
            }

            @Override
            public GenerationResult generate(GenerationRequest request) {
                throw new ProviderUnavailableException("down");
            }
        };
        var checker = new FallbackConditionChecker(new GenerativeReasoningService(
                asset -> new ModelBinding("fake", "alias"),
                (GenerativeProviderRegistry) id -> Optional.of(down), Clock.systemUTC()));

        var result = checker.check(ask(true));

        assertThat(result.outcome()).isEqualTo(FallbackConditionChecker.Result.UNAVAILABLE);
        assertThat(result.answered()).isFalse();
    }

    @Test
    void anOutOfCreditGenerationIsNotRecordedAsAnOrdinaryProviderFailure() {
        GenerativeReasoningProvider dry = new GenerativeReasoningProvider() {
            @Override
            public String id() {
                return "fake";
            }

            @Override
            public GenerationResult generate(GenerationRequest request) {
                throw new ProviderOutOfCreditException("Provider account has no credits");
            }
        };
        var checker = new FallbackConditionChecker(new GenerativeReasoningService(
                asset -> new ModelBinding("fake", "alias"),
                (GenerativeProviderRegistry) id -> Optional.of(dry), Clock.systemUTC()));

        assertThatThrownBy(() -> checker.check(ask(true)))
                .isInstanceOf(ProviderOutOfCreditException.class);
    }

    @Test
    void anAnswerWithoutAReadableConditionLineIsInvalidAndChangesNothing() {
        var garbled = checkerAnswering(request -> answer("YES", "The clause seems fine to me."));
        var wrongShape = checkerAnswering(request -> Map.of(FallbackConditionSpecs.CONDITIONS, "x"));

        assertThat(garbled.check(ask(true)).outcome()).isEqualTo(FallbackConditionChecker.Result.INVALID);
        assertThat(garbled.check(ask(true)).answered()).isFalse();
        assertThat(wrongShape.check(ask(true)).outcome()).isEqualTo(FallbackConditionChecker.Result.INVALID);
    }

    @Test
    void aStructuredPositionIsListedOnlyWhenItHasFallbackTextAndAProsePositionIsLeftToTheModel() {
        var withFallback = new com.rivell.workbench.retrieval.EvidenceItem.PolicyFacts(
                java.util.Set.of(), null, null, "English law", List.of("Swiss law if the counterparty is Swiss"), null);
        var preferredOnly = new com.rivell.workbench.retrieval.EvidenceItem.PolicyFacts(
                java.util.Set.of(), null, null, "English law", List.of(), "US law");
        var prose = new com.rivell.workbench.retrieval.EvidenceItem.PolicyFacts(
                java.util.Set.of(), null, null, null, List.of(), null);

        assertThat(FallbackConditionChecker.listed(withFallback)).isTrue();
        assertThat(FallbackConditionChecker.listed(preferredOnly)).isFalse();
        assertThat(FallbackConditionChecker.listed(prose)).isNull();
        assertThat(FallbackConditionChecker.listed(null)).isNull();
    }

    @Test
    void conditionsOutsideTheListedFallbackRangeAreIgnored() {
        var checker = checkerAnswering(request -> answer("YES",
                condition("1 | UNMET | exclusive jurisdiction | fallback one wants English courts"),
                condition("7 | MET | laws of Switzerland | an invented fallback")));

        var result = checker.check(ask(true, 1));

        assertThat(result.conditions()).singleElement().satisfies(condition ->
                assertThat(condition.fallback()).isEqualTo(1));
        var onlyInvented = checkerAnswering(request -> answer("YES",
                condition("7 | UNMET | exclusive jurisdiction | an invented fallback")));
        assertThat(onlyInvented.check(ask(true, 1)).outcome())
                .isEqualTo(FallbackConditionChecker.Result.INVALID);
    }

    @Test
    void conditionTextIsCappedBeforeItReachesAUserVisibleNote() {
        var long160 = "x".repeat(400);
        var checker = checkerAnswering(request -> answer("YES", condition("1 | UNKNOWN | - | " + long160)));

        assertThat(checker.check(ask(true)).conditions()).singleElement().satisfies(condition -> {
            assertThat(condition.condition().length()).isLessThanOrEqualTo(160);
            assertThat(condition.status()).isEqualTo(FallbackConditionSpecs.NOT_STATED);
        });
    }
}
