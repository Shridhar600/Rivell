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
 *
 * <p>An escalation trigger is the exception: it quotes the escalation sentence out of the
 * playbook unit the request carries. Returning the generic draft instead would file a
 * sentence about cooperating in good faith as the condition that forces a lawyer to be
 * involved, which is worse than reporting nothing. When the unit states no escalation
 * condition the field is left absent rather than filled with something unrelated.
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
            if ("escalationTrigger".equals(name)) {
                escalation(request.state().payload()).ifPresent(trigger -> fields.put(name, trigger));
                continue;
            }
            fields.put(name, DRAFT);
        }
        return new GenerationResult(fields,
                DemoScriptedDecisionProvider.RESOLVED_MODEL, new TokenUsage(0, 0));
    }

    private java.util.Optional<String> escalation(Object state) {
        var text = state instanceof java.util.Map<?, ?> payload
                && payload.get("text") instanceof String value ? value : null;
        if (text == null) {
            return java.util.Optional.empty();
        }
        for (var sentence : text.split("(?<=[.!?])\s+")) {
            var trimmed = sentence.trim();
            if (trimmed.toLowerCase().startsWith("escalate")) {
                return java.util.Optional.of(trimmed);
            }
        }
        return java.util.Optional.empty();
    }
}
