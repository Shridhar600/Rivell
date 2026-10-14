package com.rivell.workbench.review.check;

import java.util.Optional;

import com.rivell.workbench.intelligence.DecisionExecution;
import com.rivell.workbench.intelligence.SemanticDecisionException;
import com.rivell.workbench.intelligence.SemanticDecisionService;
import com.rivell.workbench.intelligence.YesNoAnswer;
import com.rivell.workbench.review.PolicyComparisonSpecs;

/**
 * Asks whether a clause that no published position addresses is unusual and against our
 * side (design §6c). The code owns the threshold; a provider failure yields no verdict,
 * because the playbook was silent and silence is not itself a finding.
 */
final class UncoveredClauseTriage {

    private final SemanticDecisionService decisionService;

    UncoveredClauseTriage(SemanticDecisionService decisionService) {
        this.decisionService = decisionService;
    }

    record Verdict(double probability, DecisionExecution execution) {
    }

    Optional<Verdict> assess(PolicyComparisonSpecs.PolicyComparisonState state) {
        try {
            var execution = decisionService.evaluate(PolicyComparisonSpecs.uncoveredClause(), state);
            var answer = execution.answers().get(PolicyComparisonSpecs.NEEDS_REVIEW);
            return answer instanceof YesNoAnswer yesNo
                    ? Optional.of(new Verdict(yesNo.probability(), execution))
                    : Optional.empty();
        } catch (SemanticDecisionException e) {
            return Optional.empty();
        }
    }
}
