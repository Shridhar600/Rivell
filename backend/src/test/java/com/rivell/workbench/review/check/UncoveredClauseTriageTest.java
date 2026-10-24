package com.rivell.workbench.review.check;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.rivell.workbench.intelligence.ChoiceAnswer;
import com.rivell.workbench.intelligence.DecisionExecutionRequest;
import com.rivell.workbench.intelligence.ModelBinding;
import com.rivell.workbench.intelligence.ProviderExecutionResult;
import com.rivell.workbench.intelligence.ProviderOutOfCreditException;
import com.rivell.workbench.intelligence.ProviderUnavailableException;
import com.rivell.workbench.intelligence.SemanticDecisionProvider;
import com.rivell.workbench.intelligence.SemanticDecisionService;
import com.rivell.workbench.intelligence.TokenUsage;
import com.rivell.workbench.review.PolicyComparisonSpecs;

class UncoveredClauseTriageTest {

    @Test
    void aFailedCallIsToldApartFromARealNoVerdict() {
        var failed = new UncoveredClauseTriage(service(new SemanticDecisionProvider() {
            @Override
            public String id() {
                return "fake";
            }

            @Override
            public ProviderExecutionResult execute(DecisionExecutionRequest request) {
                throw new ProviderUnavailableException("provider down");
            }
        }));
        var unavailable = failed.assess(state());
        assertThat(unavailable.unavailable()).isTrue();
        assertThat(unavailable.verdict()).isEmpty();

        var unusable = new UncoveredClauseTriage(service(new SemanticDecisionProvider() {
            @Override
            public String id() {
                return "fake";
            }

            @Override
            public ProviderExecutionResult execute(DecisionExecutionRequest request) {
                var answers = new LinkedHashMap<String, com.rivell.workbench.intelligence.SemanticAnswer>();
                answers.put(PolicyComparisonSpecs.NEEDS_REVIEW,
                        new ChoiceAnswer("YES", 0.9, Map.of("YES", 0.9)));
                return new ProviderExecutionResult(answers, "fake", new TokenUsage(1, 1));
            }
        }));
        var noVerdict = unusable.assess(state());
        assertThat(noVerdict.unavailable()).isFalse();
        assertThat(noVerdict.verdict()).isEmpty();
    }

    @Test
    void outOfCreditIsNotRecordedAsAFailedTriage() {
        var triage = new UncoveredClauseTriage(service(new SemanticDecisionProvider() {
            @Override
            public String id() {
                return "fake";
            }

            @Override
            public ProviderExecutionResult execute(DecisionExecutionRequest request) {
                throw new ProviderOutOfCreditException("Provider account has no credits");
            }
        }));

        assertThatThrownBy(() -> triage.assess(state()))
                .isInstanceOf(ProviderOutOfCreditException.class);
    }

    private static SemanticDecisionService service(SemanticDecisionProvider provider) {
        return new SemanticDecisionService(
                asset -> new ModelBinding("fake", "fake-model"),
                id -> Optional.of(provider), Clock.systemUTC());
    }

    private static PolicyComparisonSpecs.PolicyComparisonState state() {
        return new PolicyComparisonSpecs.PolicyComparisonState(
                PolicyComparisonSpecs.STATE_SCHEMA_VERSION, "MSA", "CUSTOMER",
                "Governing Law", "This agreement is governed by the laws of Delaware.", java.util.List.of());
    }
}
