package com.rivell.workbench.review.check;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.rivell.workbench.intelligence.DecisionExecution;
import com.rivell.workbench.intelligence.DecisionExecutionMetadata;
import com.rivell.workbench.intelligence.DecisionOutcome;
import com.rivell.workbench.intelligence.DecisionSpec;
import com.rivell.workbench.intelligence.ProviderOutOfCreditException;
import com.rivell.workbench.intelligence.ProviderUnavailableException;
import com.rivell.workbench.intelligence.SemanticAnswer;
import com.rivell.workbench.intelligence.SemanticDecisionService;
import com.rivell.workbench.intelligence.SemanticState;
import com.rivell.workbench.intelligence.SpecStateMismatchException;
import com.rivell.workbench.intelligence.TokenUsage;
import com.rivell.workbench.intelligence.YesNoAnswer;

/**
 * The fit contract: one bounded call for a clause's candidates, a yes/no per question, never
 * more than one call, an honest UNKNOWN when the call fails or the answer is a coin flip, and
 * separate meets/cures reasons for wording.
 */
class ClauseFitCheckTest {

    private static final ClauseFitCheck.Clause CLAUSE = new ClauseFitCheck.Clause(
            "We are the customer. The other party supplies the services.",
            "Master services agreement",
            "No Publicity",
            "No Publicity. Neither party shall publicise the Purpose or the existence of this "
                    + "Agreement without the other party's prior written consent.");

    private static final ClauseFitCheck.Candidate RULE = new ClauseFitCheck.Candidate(
            "position_1", ClauseFitCheck.Kind.RULE,
            "Mutual confidentiality, three years, standard carve-outs.",
            null,
            null, false);

    private static final ClauseFitCheck.Candidate WORDING = new ClauseFitCheck.Candidate(
            "position_1/wording", ClauseFitCheck.Kind.WORDING,
            "The total liability of Supplier shall not exceed two times the fees paid.",
            "Limitation of liability | Cap at two times the fees paid | Unlimited liability.",
            "the clause is worse for us than every fallback the position lists", false);

    private static DecisionExecution execution(DecisionSpec spec, Map<String, SemanticAnswer> answers) {
        return new DecisionExecution(UUID.randomUUID(), spec.id(), spec.version(), answers,
                new DecisionExecutionMetadata(UUID.randomUUID(), spec.id(), spec.version(),
                        spec.stateSchemaVersion(), "fake", "fake-model", "fake-resolved",
                        java.time.Instant.parse("2026-10-24T09:00:00Z"), 5L,
                        DecisionOutcome.COMPLETED, answers.size(), new TokenUsage(10, 2)));
    }

    @Test
    void oneBatchedCallCarriesEveryQuestionForTheClause() {
        var calls = new AtomicInteger();
        var seen = new DecisionSpec[1];
        var seenState = new AtomicReference<SemanticState>();
        var service = new SemanticDecisionService(asset -> null, id -> java.util.Optional.empty(),
                Clock.systemUTC()) {
            @Override
            public DecisionExecution evaluate(DecisionSpec spec, SemanticState state) {
                calls.incrementAndGet();
                seen[0] = spec;
                seenState.set(state);
                var answers = new LinkedHashMap<String, SemanticAnswer>();
                answers.put("fit_1", new YesNoAnswer(0.9));
                answers.put("fit_2_meets", new YesNoAnswer(0.1));
                answers.put("fit_2_cures", new YesNoAnswer(0.1));
                return execution(spec, answers);
            }
        };

        var outcome = new ClauseFitCheck(service).judge(CLAUSE, List.of(RULE, WORDING));

        assertThat(calls).hasValue(1);
        assertThat(outcome.questions()).isEqualTo(3);
        assertThat(outcome.called()).isTrue();
        assertThat(outcome.executionId()).isNotNull();
        assertThat(seen[0].id()).isEqualTo(ClauseFitSpecs.SPEC);
        assertThat(seen[0].version()).isEqualTo(ClauseFitSpecs.VERSION);
        assertThat(seen[0].pack().questions())
                .containsOnlyKeys("fit_1", "fit_2_meets", "fit_2_cures");
        var state = (ClauseFitSpecs.FitState) seenState.get().payload();
        assertThat(state.clause()).isEqualTo(CLAUSE.clauseText());
        assertThat(state.contractType()).isEqualTo("Master services agreement");
        assertThat(state.ourSide()).contains("customer");
        var questions = seen[0].pack().questions();
        var ruleQuestion = (com.rivell.workbench.intelligence.YesNoQuestion) questions.get("fit_1");
        var meetsQuestion = (com.rivell.workbench.intelligence.YesNoQuestion) questions.get(
                "fit_2_meets");
        var curesQuestion = (com.rivell.workbench.intelligence.YesNoQuestion) questions.get(
                "fit_2_cures");
        assertThat(ruleQuestion.instructions()).contains("Mutual confidentiality, three years");
        assertThat(meetsQuestion.instructions()).contains("two times the fees paid")
                .contains("Unlimited liability");
        assertThat(curesQuestion.instructions()).contains("worse for us than every fallback")
                .contains("two times the fees paid");

        assertThat(outcome.judgments().get("position_1").verdict())
                .isEqualTo(ClauseFitCheck.Verdict.FITS);
        assertThat(outcome.judgments().get("position_1").reasons())
                .containsExactly(ClauseFitCheck.Reason.GOVERNS);
        assertThat(outcome.judgments().get("position_1/wording").verdict())
                .isEqualTo(ClauseFitCheck.Verdict.DOES_NOT_FIT);
        assertThat(outcome.judgments().get("position_1/wording").reasons())
                .containsExactly(ClauseFitCheck.Reason.MEETS_POSITION_NO,
                        ClauseFitCheck.Reason.CURES_GAP_NO);
        assertThat(outcome.judgments().get("position_1/wording").quote())
                .contains("Neither party shall publicise");
    }

    @Test
    void aSingleExactRuleSkipsTheQuestion() {
        var calls = new AtomicInteger();
        var service = new SemanticDecisionService(asset -> null, id -> java.util.Optional.empty(),
                Clock.systemUTC()) {
            @Override
            public DecisionExecution evaluate(DecisionSpec spec, SemanticState state) {
                calls.incrementAndGet();
                throw new AssertionError("the exact rule must not ask");
            }
        };
        var exact = new ClauseFitCheck.Candidate("position_1", ClauseFitCheck.Kind.RULE,
                "rule text", null, null, true);

        var outcome = new ClauseFitCheck(service).judge(CLAUSE, List.of(exact));

        assertThat(calls).hasValue(0);
        assertThat(outcome.called()).isFalse();
        assertThat(outcome.questions()).isZero();
        assertThat(outcome.skipped()).isEqualTo(1);
        assertThat(outcome.judgments().get("position_1").verdict())
                .isEqualTo(ClauseFitCheck.Verdict.FITS);
        assertThat(outcome.judgments().get("position_1").reasons())
                .containsExactly(ClauseFitCheck.Reason.EXACT_METADATA_MATCH);
    }

    @Test
    void aSingleExactWordingIsStillAsked() {
        var calls = new AtomicInteger();
        var seen = new DecisionSpec[1];
        var service = new SemanticDecisionService(asset -> null, id -> java.util.Optional.empty(),
                Clock.systemUTC()) {
            @Override
            public DecisionExecution evaluate(DecisionSpec spec, SemanticState state) {
                calls.incrementAndGet();
                seen[0] = spec;
                return execution(spec, Map.of(
                        "fit_1_meets", new YesNoAnswer(0.9),
                        "fit_1_cures", new YesNoAnswer(0.9)));
            }
        };
        var exact = new ClauseFitCheck.Candidate("position_1/wording",
                ClauseFitCheck.Kind.WORDING, "wording text", "position text", "the gap", true);

        var outcome = new ClauseFitCheck(service).judge(CLAUSE, List.of(exact));

        // metadata finds a candidate; it does not prove the wording meets the position and cures
        // this gap, so a wording is never skipped
        assertThat(calls).hasValue(1);
        assertThat(seen[0].pack().questions()).hasSize(2);
        assertThat(outcome.skipped()).isZero();
        assertThat(outcome.judgments().get("position_1/wording").verdict())
                .isEqualTo(ClauseFitCheck.Verdict.FITS);
    }

    @Test
    void aWordingThatMeetsThePositionButDoesNotCureTheGapSaysBoth() {
        var calls = new AtomicInteger();
        var service = new SemanticDecisionService(asset -> null, id -> java.util.Optional.empty(),
                Clock.systemUTC()) {
            @Override
            public DecisionExecution evaluate(DecisionSpec spec, SemanticState state) {
                calls.incrementAndGet();
                return execution(spec, Map.of(
                        "fit_1_meets", new YesNoAnswer(0.9),
                        "fit_1_cures", new YesNoAnswer(0.1)));
            }
        };

        var outcome = new ClauseFitCheck(service).judge(CLAUSE, List.of(WORDING));

        // meets the position but does not cure the gap: offered unchecked, never replaced
        assertThat(outcome.judgments().get("position_1/wording").verdict())
                .isEqualTo(ClauseFitCheck.Verdict.UNKNOWN);
        assertThat(outcome.judgments().get("position_1/wording").reasons())
                .containsExactly(ClauseFitCheck.Reason.MEETS_POSITION_YES,
                        ClauseFitCheck.Reason.CURES_GAP_NO);
    }

    @Test
    void aWordingThatCuresTheGapButFallsShortOfThePositionSaysBoth() {
        var service = new SemanticDecisionService(asset -> null, id -> java.util.Optional.empty(),
                Clock.systemUTC()) {
            @Override
            public DecisionExecution evaluate(DecisionSpec spec, SemanticState state) {
                return execution(spec, Map.of(
                        "fit_1_meets", new YesNoAnswer(0.1),
                        "fit_1_cures", new YesNoAnswer(0.9)));
            }
        };

        var outcome = new ClauseFitCheck(service).judge(CLAUSE, List.of(WORDING));

        assertThat(outcome.judgments().get("position_1/wording").verdict())
                .isEqualTo(ClauseFitCheck.Verdict.DOES_NOT_FIT);
        assertThat(outcome.judgments().get("position_1/wording").reasons())
                .containsExactly(ClauseFitCheck.Reason.MEETS_POSITION_NO,
                        ClauseFitCheck.Reason.CURES_GAP_YES);
    }

    @Test
    void aWordingUncertainOnBothSlotsIsUnknownWithBothSlotsMarked() {
        var service = new SemanticDecisionService(asset -> null, id -> java.util.Optional.empty(),
                Clock.systemUTC()) {
            @Override
            public DecisionExecution evaluate(DecisionSpec spec, SemanticState state) {
                return execution(spec, Map.of(
                        "fit_1_meets", new YesNoAnswer(0.5),
                        "fit_1_cures", new YesNoAnswer(0.5)));
            }
        };

        var outcome = new ClauseFitCheck(service).judge(CLAUSE, List.of(WORDING));

        assertThat(outcome.judgments().get("position_1/wording").verdict())
                .isEqualTo(ClauseFitCheck.Verdict.UNKNOWN);
        assertThat(outcome.judgments().get("position_1/wording").reasons())
                .containsExactly(ClauseFitCheck.Reason.MEETS_POSITION_UNKNOWN,
                        ClauseFitCheck.Reason.CURES_GAP_UNKNOWN);
    }

    @Test
    void aSingleRuleWithoutExactMetadataIsStillAsked() {
        var calls = new AtomicInteger();
        var service = new SemanticDecisionService(asset -> null, id -> java.util.Optional.empty(),
                Clock.systemUTC()) {
            @Override
            public DecisionExecution evaluate(DecisionSpec spec, SemanticState state) {
                calls.incrementAndGet();
                return execution(spec, Map.of("fit_1", new YesNoAnswer(0.8)));
            }
        };

        var outcome = new ClauseFitCheck(service).judge(CLAUSE, List.of(RULE));

        assertThat(calls).hasValue(1);
        assertThat(outcome.skipped()).isZero();
        assertThat(outcome.judgments().get("position_1").verdict())
                .isEqualTo(ClauseFitCheck.Verdict.FITS);
    }

    @Test
    void aFailedCallIsUnknownForEveryCandidateNeverFits() {
        var service = new SemanticDecisionService(asset -> null, id -> java.util.Optional.empty(),
                Clock.systemUTC()) {
            @Override
            public DecisionExecution evaluate(DecisionSpec spec, SemanticState state) {
                throw new ProviderUnavailableException("provider down");
            }
        };

        var outcome = new ClauseFitCheck(service).judge(CLAUSE, List.of(RULE, WORDING));

        assertThat(outcome.called()).isTrue();
        assertThat(outcome.judgments().get("position_1").verdict())
                .isEqualTo(ClauseFitCheck.Verdict.UNKNOWN);
        assertThat(outcome.judgments().get("position_1").reasons())
                .containsExactly(ClauseFitCheck.Reason.UNAVAILABLE);
        assertThat(outcome.judgments().get("position_1/wording").verdict())
                .isEqualTo(ClauseFitCheck.Verdict.UNKNOWN);
        assertThat(outcome.judgments().get("position_1/wording").reasons())
                .containsExactly(ClauseFitCheck.Reason.MEETS_POSITION_UNKNOWN,
                        ClauseFitCheck.Reason.CURES_GAP_UNKNOWN,
                        ClauseFitCheck.Reason.UNAVAILABLE);
    }

    @Test
    void aCoinFlipAnswerIsUnknownNotFits() {
        var service = new SemanticDecisionService(asset -> null, id -> java.util.Optional.empty(),
                Clock.systemUTC()) {
            @Override
            public DecisionExecution evaluate(DecisionSpec spec, SemanticState state) {
                return execution(spec, Map.of("fit_1", new YesNoAnswer(0.5)));
            }
        };

        var outcome = new ClauseFitCheck(service).judge(CLAUSE, List.of(RULE));

        assertThat(outcome.judgments().get("position_1").verdict())
                .isEqualTo(ClauseFitCheck.Verdict.UNKNOWN);
        assertThat(outcome.judgments().get("position_1").reasons())
                .containsExactly(ClauseFitCheck.Reason.UNCERTAIN);
    }

    @Test
    void outOfCreditIsNotSwallowedIntoUnknown() {
        var service = new SemanticDecisionService(asset -> null, id -> java.util.Optional.empty(),
                Clock.systemUTC()) {
            @Override
            public DecisionExecution evaluate(DecisionSpec spec, SemanticState state) {
                throw new ProviderOutOfCreditException("no credit");
            }
        };

        assertThatThrownBy(() -> new ClauseFitCheck(service).judge(CLAUSE, List.of(RULE)))
                .isInstanceOf(ProviderOutOfCreditException.class);
    }

    @Test
    void aDecisionContractErrorIsNotSwallowedIntoUnknown() {
        var service = new SemanticDecisionService(asset -> null, id -> java.util.Optional.empty(),
                Clock.systemUTC()) {
            @Override
            public DecisionExecution evaluate(DecisionSpec spec, SemanticState state) {
                throw new SpecStateMismatchException("wrong state schema");
            }
        };

        assertThatThrownBy(() -> new ClauseFitCheck(service).judge(CLAUSE, List.of(RULE)))
                .isInstanceOf(SpecStateMismatchException.class);
    }

    @Test
    void noCandidatesAskNothing() {
        var calls = new AtomicInteger();
        var service = new SemanticDecisionService(asset -> null, id -> java.util.Optional.empty(),
                Clock.systemUTC()) {
            @Override
            public DecisionExecution evaluate(DecisionSpec spec, SemanticState state) {
                calls.incrementAndGet();
                return execution(spec, Map.of());
            }
        };

        var outcome = new ClauseFitCheck(service).judge(CLAUSE, List.of());

        assertThat(calls).hasValue(0);
        assertThat(outcome.called()).isFalse();
        assertThat(outcome.judgments()).isEmpty();
    }
}
