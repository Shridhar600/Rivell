package com.rivell.workbench.platform.demo;

import java.util.LinkedHashMap;

import com.rivell.workbench.intelligence.GenerationRequest;
import com.rivell.workbench.intelligence.GenerationResult;
import com.rivell.workbench.intelligence.GenerativeReasoningProvider;
import com.rivell.workbench.intelligence.TokenUsage;

/**
 * Deterministic generative provider for local demo data only. Returns a fixed, obviously
 * synthetic clause so an open-ended escalation produces a real candidate without a model
 * or a network call.
 */
public final class DemoScriptedGenerativeProvider implements GenerativeReasoningProvider {

    private static final String DRAFT =
            "The parties agree to cooperate in good faith to resolve the matter promptly.";

    @Override
    public String id() {
        return "demo-scripted";
    }

    @Override
    public GenerationResult generate(GenerationRequest request) {
        var fields = new LinkedHashMap<String, String>();
        for (var name : request.spec().fields().keySet()) {
            fields.put(name, DRAFT);
        }
        return new GenerationResult(fields,
                DemoScriptedDecisionProvider.RESOLVED_MODEL, new TokenUsage(0, 0));
    }
}
