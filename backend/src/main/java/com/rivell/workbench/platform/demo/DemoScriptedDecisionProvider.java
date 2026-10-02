package com.rivell.workbench.platform.demo;

import java.util.LinkedHashMap;

import com.rivell.workbench.intelligence.ChoiceAnswer;
import com.rivell.workbench.intelligence.ChoiceQuestion;
import com.rivell.workbench.intelligence.DecisionExecutionRequest;
import com.rivell.workbench.intelligence.ProviderExecutionResult;
import com.rivell.workbench.intelligence.SemanticAnswer;
import com.rivell.workbench.intelligence.SemanticDecisionProvider;
import com.rivell.workbench.intelligence.TokenUsage;
import com.rivell.workbench.intelligence.YesNoAnswer;

/**
 * Deterministic decision provider for local demo data only.
 *
 * <p>Without a provider the analysis layer fails closed, so a review can only ever reach
 * COMPLETED or FAILED, never a truthful "finished, and here is what it found". This
 * provider answers every question from the question pack itself, with no network call and
 * no model, so the real review pipeline runs end to end offline and produces real runs,
 * real check executions and real findings.
 *
 * <p>It is registered only when demo seeding is explicitly enabled, and it deliberately
 * returns a high but not absolute probability: the domain's fail-closed bands may still
 * record a check as degraded, which is the honest outcome for an uncalibrated provider.
 */
public final class DemoScriptedDecisionProvider implements SemanticDecisionProvider {

    static final double PROBABILITY = 0.9;
    static final String RESOLVED_MODEL = "rivell-demo-scripted-1";

    @Override
    public String id() {
        return "demo-scripted";
    }

    @Override
    public ProviderExecutionResult execute(DecisionExecutionRequest request) {
        var answers = new LinkedHashMap<String, SemanticAnswer>();
        for (var entry : request.pack().questions().entrySet()) {
            var question = entry.getValue();
            if (question instanceof ChoiceQuestion choice) {
                var selected = choice.criteria().keySet().iterator().next();
                answers.put(entry.getKey(),
                        new ChoiceAnswer(selected, PROBABILITY, java.util.Map.of(selected, PROBABILITY)));
            } else {
                answers.put(entry.getKey(), new YesNoAnswer(PROBABILITY));
            }
        }
        return new ProviderExecutionResult(answers, RESOLVED_MODEL, new TokenUsage(0, 0));
    }
}
