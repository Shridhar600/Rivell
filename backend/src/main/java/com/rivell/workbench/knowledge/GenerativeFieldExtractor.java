package com.rivell.workbench.knowledge;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.rivell.workbench.intelligence.GenerativeReasoningService;
import com.rivell.workbench.intelligence.SemanticDecisionService;
import com.rivell.workbench.intelligence.YesNoAnswer;

/**
 * Typed open-ended extraction with independent verification. The generative result is
 * never trusted merely because it parsed: an orthogonal Jev question checks the exact
 * source text. DISPUTED/UNCERTAIN results are recorded as interpretations but never
 * become candidate semantic field values.
 */
public class GenerativeFieldExtractor {

    private final GenerativeReasoningService reasoningService;
    private final SemanticDecisionService decisionService;
    private final SemanticPolicyProperties properties;

    public GenerativeFieldExtractor(
            GenerativeReasoningService reasoningService,
            SemanticDecisionService decisionService,
            SemanticPolicyProperties properties) {
        this.reasoningService = reasoningService;
        this.decisionService = decisionService;
        this.properties = properties;
    }

    public Optional<VerifiedValue> extractEscalationTrigger(
            M4SemanticStates.PlaybookUnitState state) {
        var source = bounded(state.text());
        if (source == null || source.isBlank()) {
            // A unit with no prose has nothing to extract from and nothing to verify a claim
            // against. Returning empty keeps the outcome explicit at the caller instead of
            // asking a model to invent a condition, and stops the null reaching the bound.
            return Optional.empty();
        }
        var generation = reasoningService.generate(
                M4ReasoningSpecs.escalationTrigger(), state);
        var claimed = generation.fields().get("escalationTrigger");

        var verificationExecution = decisionService.evaluate(
                M4SemanticSpecs.fieldVerification(),
                new M4SemanticStates.FieldVerificationState(source, claimed));
        var supports = verificationExecution.answers().get("source_supports");
        if (!(supports instanceof YesNoAnswer yesNo)) {
            return Optional.empty();
        }
        var verification = classify(yesNo.probability());
        if (verification == VerificationState.VERIFIED) {
            return Optional.of(new VerifiedValue(
                    claimed, verification,
                    generationExecutionId(generation),
                    verificationExecution.metadata().executionId()));
        }
        return Optional.of(new VerifiedValue(
                claimed, verification,
                generationExecutionId(generation),
                verificationExecution.metadata().executionId()));
    }

    private VerificationState classify(double probability) {
        if (probability >= properties.verificationVerified()) {
            return VerificationState.VERIFIED;
        }
        if (probability < properties.verificationDisputed()) {
            return VerificationState.DISPUTED;
        }
        return VerificationState.UNCERTAIN;
    }

    private static String bounded(String text) {
        if (text == null) {
            return null;
        }
        return text.length() > 1200 ? text.substring(0, 1200) : text;
    }

    private static UUID generationExecutionId(GenerativeReasoningService.GenerativeExecution generation) {
        return generation.metadata().executionId();
    }

    public record VerifiedValue(
            String value,
            VerificationState verification,
            UUID generationExecutionId,
            UUID verificationExecutionId) {
    }
}
