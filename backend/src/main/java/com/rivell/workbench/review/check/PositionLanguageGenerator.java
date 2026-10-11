package com.rivell.workbench.review.check;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.rivell.workbench.intelligence.DecisionExecutionMetadata;
import com.rivell.workbench.intelligence.GenerativeReasoningService;
import com.rivell.workbench.intelligence.SemanticAnswer;
import com.rivell.workbench.intelligence.SemanticDecisionException;
import com.rivell.workbench.intelligence.SemanticDecisionService;
import com.rivell.workbench.intelligence.YesNoAnswer;
import com.rivell.workbench.review.M7DecisionSpecs;
import com.rivell.workbench.review.M7ReasoningSpecs;

/**
 * Machine language for a flagged position that has no approved clause-library wording
 * (design §6a item 5): the generative model proposes text, code validates its shape, and
 * a bounded decision verifies it against the position. Any failure yields no proposal, so
 * the finding escalates to a human instead.
 */
final class PositionLanguageGenerator {

    private static final int MAX_CANDIDATE_LENGTH = 2000;
    private static final double MAX_COPY_OVERLAP = 0.9;
    private static final double TRUE_BAND = 0.6;
    private static final double FALSE_BAND = 0.4;

    private final GenerativeReasoningService reasoningService;
    private final SemanticDecisionService decisionService;

    PositionLanguageGenerator(
            GenerativeReasoningService reasoningService, SemanticDecisionService decisionService) {
        this.reasoningService = reasoningService;
        this.decisionService = decisionService;
    }

    record Proposal(
            String proposedLanguage, String explanation, UUID generationExecutionId,
            DecisionExecutionMetadata metadata, Verification verification) {
    }

    record Verification(
            String outcome, UUID executionId, double satisfiesProbability,
            double newRiskProbability) {
    }

    /** Empty when generation fails, the output fails validation, or the model is down. */
    Optional<Proposal> propose(String clauseText, String positionText) {
        try {
            var generation = reasoningService.generate(M7ReasoningSpecs.openEndedPosition(),
                    new M7ReasoningSpecs.OpenEndedPositionState(
                            M7ReasoningSpecs.OPEN_ENDED_STATE_SCHEMA, clauseText, positionText));
            var proposed = generation.fields().get(M7ReasoningSpecs.PROPOSED_LANGUAGE);
            var explanation = generation.fields().get(M7ReasoningSpecs.EXPLANATION);
            if (!valid(proposed, explanation, clauseText)) {
                return Optional.empty();
            }
            return Optional.of(new Proposal(proposed, explanation, generation.executionId(),
                    generation.metadata(), verify(proposed, positionText, clauseText)));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private Verification verify(String proposed, String positionText, String clauseText) {
        try {
            var execution = decisionService.evaluate(M7DecisionSpecs.findingVerification(),
                    new M7DecisionSpecs.FindingVerificationState(
                            M7DecisionSpecs.FINDING_VERIFICATION_STATE_SCHEMA,
                            proposed, positionText, clauseText));
            var satisfies = probability(execution.answers().get(M7DecisionSpecs.SATISFIES_POLICY));
            var newRisk = probability(execution.answers().get(M7DecisionSpecs.INTRODUCED_NEW_RISK));
            var outcome = newRisk >= FALSE_BAND ? "DISPUTED"
                    : satisfies > TRUE_BAND ? "VERIFIED" : "UNCERTAIN";
            return new Verification(outcome, execution.executionId(), satisfies, newRisk);
        } catch (SemanticDecisionException e) {
            return new Verification("UNCERTAIN", null, 0.5, 0.5);
        }
    }

    private static double probability(SemanticAnswer answer) {
        return answer instanceof YesNoAnswer yesNo ? yesNo.probability() : 0.5;
    }

    private static boolean valid(String proposed, String explanation, String clauseText) {
        return proposed != null && !proposed.isBlank()
                && explanation != null && !explanation.isBlank()
                && proposed.length() <= MAX_CANDIDATE_LENGTH
                && explanation.length() <= MAX_CANDIDATE_LENGTH
                && !nearCopy(proposed, clauseText);
    }

    private static boolean nearCopy(String proposed, String source) {
        var proposedTokens = tokens(proposed);
        var sourceTokens = tokens(source);
        if (proposedTokens.isEmpty() || sourceTokens.isEmpty()) {
            return false;
        }
        var intersection = new LinkedHashSet<>(proposedTokens);
        intersection.retainAll(sourceTokens);
        var smaller = Math.min(proposedTokens.size(), sourceTokens.size());
        return (double) intersection.size() / smaller >= MAX_COPY_OVERLAP;
    }

    private static Set<String> tokens(String value) {
        return Arrays.stream(Normalizer.normalize(value, Normalizer.Form.NFKC)
                        .toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9]+", " ")
                        .trim()
                        .split("\\s+"))
                .filter(token -> !token.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
