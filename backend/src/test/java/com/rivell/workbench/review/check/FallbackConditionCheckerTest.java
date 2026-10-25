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
import com.rivell.workbench.review.FallbackConditionSpecs;

class FallbackConditionCheckerTest {

    private static final String CLAUSE = "This Agreement is governed by the laws of Switzerland. "
            + "The courts of Zurich have exclusive jurisdiction.";
    private static final String POSITION = "Compare the clause with our playbook position on Governing Law.\n"
            + "Preferred: English law\nFallback 1: Swiss law if the counterparty is Swiss\nWalk-away: US law";
    private static final List<String> FACTS = List.of("Party: Helvetia Cloud AG (SUPPLIER)",
            "Party: Studio Ltd of London (CUSTOMER)", "Governing law (stated): Switzerland");

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

    private static Map<String, String> answer(String listed, String... lines) {
        return Map.of(FallbackConditionSpecs.FALLBACK_LISTED, listed,
                FallbackConditionSpecs.CONDITIONS, String.join("\n", lines));
    }

    @Test
    void anUnmetConditionWithAQuoteTheFactsHoldRaisesTheClauseToBeyondFallback() {
        var checker = checkerAnswering(request -> answer("YES",
                "1 | UNMET | Studio Ltd of London (CUSTOMER) | the counterparty is Swiss"));

        var result = checker.check(ask(true));

        assertThat(result.outcome()).isEqualTo(FallbackConditionChecker.Result.BEYOND);
        assertThat(result.raises()).isTrue();
        assertThat(result.fallbackListed()).isTrue();
        assertThat(result.conditions()).singleElement().satisfies(condition -> {
            assertThat(condition.status()).isEqualTo(FallbackConditionSpecs.UNMET);
            assertThat(condition.quote()).isEqualTo("Studio Ltd of London (CUSTOMER)");
        });
        assertThat(result.executionId()).isNotNull();
    }

    @Test
    void anUnmetConditionWithAQuoteTheClauseHoldsAlsoRaises() {
        var checker = checkerAnswering(request -> answer("YES",
                "1 | UNMET | exclusive jurisdiction | the courts are not Swiss courts of the supplier"));

        assertThat(checker.check(ask(true)).raises()).isTrue();
    }

    @Test
    void anUnmetConditionWhoseQuoteNeitherTheClauseNorTheFactsHoldDoesNotRaise() {
        var checker = checkerAnswering(request -> answer("YES",
                "1 | UNMET | the parties agree to arbitration in Paris | the forum is Swiss"));

        var result = checker.check(ask(true));

        assertThat(result.raises()).isFalse();
        assertThat(result.outcome()).isEqualTo(FallbackConditionChecker.Result.WITHIN);
        // an invented quote is no evidence: the condition is recorded as unknown
        assertThat(result.unknown()).singleElement()
                .satisfies(condition -> assertThat(condition.text()).isEqualTo("the forum is Swiss"));
    }

    @Test
    void anUnknownConditionNeverRaisesAndIsKeptForTheLookNote() {
        var checker = checkerAnswering(request -> answer("YES",
                "1 | MET | laws of Switzerland | the law is Swiss",
                "1 | UNKNOWN | - | the work is delivered from our UK studio"));

        var result = checker.check(ask(true));

        assertThat(result.raises()).isFalse();
        assertThat(result.outcome()).isEqualTo(FallbackConditionChecker.Result.WITHIN);
        assertThat(result.unknown()).singleElement()
                .satisfies(condition -> assertThat(condition.text())
                        .isEqualTo("the work is delivered from our UK studio"));
    }

    @Test
    void whenEveryConditionIsMetTheClauseStaysWithinWithNothingUnknown() {
        var checker = checkerAnswering(request -> answer("YES",
                "1 | MET | laws of Switzerland | the law is Swiss",
                "1 | MET | Helvetia Cloud AG (SUPPLIER) | the counterparty is Swiss"));

        var result = checker.check(ask(true));

        assertThat(result.raises()).isFalse();
        assertThat(result.unknown()).isEmpty();
        assertThat(result.conditions()).hasSize(2);
    }

    @Test
    void aMetConditionWithoutAQuoteTheTextHoldsCountsAsUnknown() {
        var checker = checkerAnswering(request -> answer("YES",
                "1 | MET | the law is certainly Swiss | the law is Swiss"));

        assertThat(checker.check(ask(true)).unknown()).hasSize(1);
    }

    @Test
    void severalFallbacksOnlyRaiseWhenEveryListedFallbackFails() {
        var oneStillHolds = checkerAnswering(request -> answer("YES",
                "1 | UNMET | exclusive jurisdiction | fallback one wants English courts",
                "2 | MET | laws of Switzerland | fallback two wants Swiss law"));
        var allFail = checkerAnswering(request -> answer("YES",
                "1 | UNMET | exclusive jurisdiction | fallback one wants English courts",
                "2 | UNMET | Studio Ltd of London (CUSTOMER) | fallback two wants a Swiss counterparty"));

        assertThat(oneStillHolds.check(ask(true, 2)).raises()).isFalse();
        assertThat(allFail.check(ask(true, 2)).raises()).isTrue();
    }

    @Test
    void aPositionWithNoListedFallbackIsCheckedAgainstThePreferredPosition() {
        var fails = checkerAnswering(request -> answer("NO",
                "1 | UNMET | laws of Switzerland | the clause meets the preferred position"));
        var meets = checkerAnswering(request -> answer("NO",
                "1 | MET | laws of Switzerland | the clause meets the preferred position"));
        var unsure = checkerAnswering(request -> answer("NO",
                "1 | UNKNOWN | - | the clause meets the preferred position"));

        var raised = fails.check(ask(false));
        assertThat(raised.raises()).isTrue();
        assertThat(raised.fallbackListed()).isFalse();
        assertThat(meets.check(ask(false)).raises()).isFalse();
        // UNKNOWN never raises severity, here either (G4)
        assertThat(unsure.check(ask(false)).raises()).isFalse();
    }

    @Test
    void forAProsePositionTheModelSaysWhetherAFallbackIsListed() {
        var checker = checkerAnswering(request -> answer("NO",
                "1 | UNMET | laws of Switzerland | the clause meets the preferred position"));

        var result = checker.check(ask(null));

        assertThat(result.fallbackListed()).isFalse();
        assertThat(result.raises()).isTrue();
    }

    @Test
    void theStateCarriesTheClauseThePositionAndTheContractFacts() {
        var checker = checkerAnswering(request -> answer("YES",
                "1 | MET | laws of Switzerland | the law is Swiss"));

        checker.check(ask(true));

        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request.spec().id()).isEqualTo(FallbackConditionSpecs.SPEC_ID);
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
        assertThat(result.raises()).isFalse();
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
        assertThat(garbled.check(ask(true)).raises()).isFalse();
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
    void aListedFallbackTheModelDidNotReportIsNotAFailedFallback() {
        // two fallbacks are listed; the model reports only the first as unmet: the second was never checked
        var checker = checkerAnswering(request -> answer("YES",
                "1 | UNMET | exclusive jurisdiction | fallback one wants English courts"));

        assertThat(checker.check(ask(true, 2)).raises()).isFalse();
        assertThat(checker.check(ask(true, 1)).raises()).isTrue();
    }

    @Test
    void fallbackNumbersOutsideTheListedRangeAreIgnored() {
        var checker = checkerAnswering(request -> answer("YES",
                "1 | UNMET | exclusive jurisdiction | fallback one wants English courts",
                "7 | MET | laws of Switzerland | an invented fallback"));

        assertThat(checker.check(ask(true, 1)).raises()).isTrue();
        var onlyInvented = checkerAnswering(request -> answer("YES",
                "7 | UNMET | exclusive jurisdiction | an invented fallback"));
        assertThat(onlyInvented.check(ask(true, 1)).raises()).isFalse();
    }

    @Test
    void forAProsePositionTheFallbackNumbersMustRunFromOneWithoutGaps() {
        var gap = checkerAnswering(request -> answer("YES",
                "1 | UNMET | exclusive jurisdiction | fallback one wants English courts",
                "3 | UNMET | laws of Switzerland | fallback three wants English law"));
        var complete = checkerAnswering(request -> answer("YES",
                "1 | UNMET | exclusive jurisdiction | fallback one wants English courts",
                "2 | UNMET | laws of Switzerland | fallback two wants English law"));

        assertThat(gap.check(ask(null, null)).raises()).isFalse();
        assertThat(complete.check(ask(null, null)).raises()).isTrue();
    }

    @Test
    void conditionTextIsCappedBeforeItReachesAUserVisibleNote() {
        var long160 = "x".repeat(400);
        var checker = checkerAnswering(request -> answer("YES", "1 | UNKNOWN | - | " + long160));

        assertThat(checker.check(ask(true)).unknown()).singleElement()
                .satisfies(condition -> assertThat(condition.text().length()).isLessThanOrEqualTo(160));
    }
}
