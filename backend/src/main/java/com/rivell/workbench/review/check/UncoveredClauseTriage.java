package com.rivell.workbench.review.check;

import java.util.Optional;

import com.rivell.workbench.intelligence.DecisionExecution;
import com.rivell.workbench.intelligence.SemanticDecisionException;
import com.rivell.workbench.intelligence.SemanticDecisionService;
import com.rivell.workbench.intelligence.YesNoAnswer;
import com.rivell.workbench.review.PolicyComparisonSpecs;

/**
 * Asks whether a clause that no published position addresses is unusual and against our
 * side (design §6c). The code owns the threshold; a provider failure is told apart from a
 * genuine "no verdict", because silence from the playbook is not itself a finding and a
 * failed call must not read as a clean clause.
 */
final class UncoveredClauseTriage {

    private final SemanticDecisionService decisionService;

    UncoveredClauseTriage(SemanticDecisionService decisionService) {
        this.decisionService = decisionService;
    }

    record Verdict(double probability, DecisionExecution execution) {
    }

    /**
     * {@code unavailable}: the call failed, so no verdict exists. {@code verdict}: empty when
     * the provider answered but not in a usable shape.
     */
    record Assessment(Optional<Verdict> verdict, boolean unavailable) {

        static Assessment failed() {
            return new Assessment(Optional.empty(), true);
        }

        static Assessment noVerdict() {
            return new Assessment(Optional.empty(), false);
        }
    }

    Assessment assess(PolicyComparisonSpecs.PolicyComparisonState state) {
        try {
            var execution = decisionService.evaluate(PolicyComparisonSpecs.uncoveredClause(), state);
            var answer = execution.answers().get(PolicyComparisonSpecs.NEEDS_REVIEW);
            return answer instanceof YesNoAnswer yesNo
                    ? new Assessment(Optional.of(new Verdict(yesNo.probability(), execution)), false)
                    : Assessment.noVerdict();
        } catch (SemanticDecisionException e) {
            if (e instanceof com.rivell.workbench.intelligence.ProviderOutOfCreditException) {
                throw e;
            }
            return Assessment.failed();
        }
    }
}
