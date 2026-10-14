package com.rivell.workbench.review.check;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

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
 * (design §6a item 5): the generative model proposes a new clause body, code validates its
 * shape, and a bounded decision verifies it against the position and against what it removes
 * from and adds to the clause. Any failure yields no proposal, so the finding escalates to a
 * human instead.
 */
final class PositionLanguageGenerator {

    private static final int MAX_PROPOSED_LENGTH = 8000;
    private static final int MAX_EXPLANATION_LENGTH = 2000;
    private static final double TRUE_BAND = 0.6;
    /** A removal or addition judged at least this likely to hurt us fails closed. */
    private static final double HURTS_BAND = 0.5;
    private static final List<String> NOTHING = List.of("(nothing)");
    /** Editing instructions and labels a model sometimes writes instead of contract text. */
    private static final Pattern DRAFTING_NOTE = Pattern.compile(
            "(?i)\\b(?:replace|delete|insert|amend)\\s+(?:the\\s+)?(?:sections?|clauses?|paragraphs?"
                    + "|sub-?clauses?|final|first|last|existing|following)\\b"
                    + "|\\bwith the following\\b|\\bcandidate language\\b|\\(not approved\\)");

    private final GenerativeReasoningService reasoningService;
    private final SemanticDecisionService decisionService;

    PositionLanguageGenerator(
            GenerativeReasoningService reasoningService, SemanticDecisionService decisionService) {
        this.reasoningService = reasoningService;
        this.decisionService = decisionService;
    }

    /**
     * One clause to redraft. {@code heading} is what stays in the document (for a run-in heading,
     * only its heading part); {@code body} is the clause text a replacement deletes.
     */
    record Draft(String ourSide, String contractType, String heading, String body, String positionText) {
    }

    record Proposal(
            String proposedLanguage, String explanation, UUID generationExecutionId,
            DecisionExecutionMetadata metadata, Verification verification) {
    }

    record Verification(
            String outcome, UUID executionId, double satisfiesProbability,
            double removalHurtsProbability, double additionHurtsProbability) {
    }

    /** Empty when generation fails, the output fails validation, or the model is down. */
    Optional<Proposal> propose(Draft draft) {
        try {
            var generation = reasoningService.generate(M7ReasoningSpecs.openEndedPosition(),
                    new M7ReasoningSpecs.OpenEndedPositionState(
                            M7ReasoningSpecs.OPEN_ENDED_STATE_SCHEMA, draft.ourSide(),
                            draft.contractType(), draft.heading(), draft.body(),
                            draft.positionText()));
            var proposed = withoutHeading(
                    generation.fields().get(M7ReasoningSpecs.PROPOSED_LANGUAGE), draft.heading());
            var explanation = generation.fields().get(M7ReasoningSpecs.EXPLANATION);
            if (!valid(proposed, explanation, draft.body())) {
                return Optional.empty();
            }
            return Optional.of(new Proposal(proposed, explanation, generation.executionId(),
                    generation.metadata(), verify(proposed, draft)));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private Verification verify(String proposed, Draft draft) {
        var diff = ClauseTextDiff.of(draft.body(), proposed);
        try {
            var execution = decisionService.evaluate(M7DecisionSpecs.findingVerification(),
                    new M7DecisionSpecs.FindingVerificationState(
                            M7DecisionSpecs.FINDING_VERIFICATION_STATE_SCHEMA, draft.ourSide(),
                            draft.positionText(), proposed,
                            diff.removed().isEmpty() ? NOTHING : diff.removed(),
                            diff.added().isEmpty() ? NOTHING : diff.added()));
            var satisfies = probability(execution.answers().get(M7DecisionSpecs.SATISFIES_POLICY));
            var removal = probability(execution.answers().get(M7DecisionSpecs.REMOVAL_HURTS_US));
            var addition = probability(execution.answers().get(M7DecisionSpecs.ADDITION_HURTS_US));
            var outcome = removal >= HURTS_BAND || addition >= HURTS_BAND ? "DISPUTED"
                    : satisfies > TRUE_BAND ? "VERIFIED" : "UNCERTAIN";
            return new Verification(outcome, execution.executionId(), satisfies, removal, addition);
        } catch (SemanticDecisionException e) {
            return new Verification("UNCERTAIN", null, 0.5, 0.5, 0.5);
        }
    }

    private static double probability(SemanticAnswer answer) {
        return answer instanceof YesNoAnswer yesNo ? yesNo.probability() : 0.5;
    }

    static boolean valid(String proposed, String explanation, String body) {
        return proposed != null && !proposed.isBlank()
                && explanation != null && !explanation.isBlank()
                && proposed.length() <= MAX_PROPOSED_LENGTH
                && explanation.length() <= MAX_EXPLANATION_LENGTH
                && !ClauseTextDiff.key(proposed).equals(ClauseTextDiff.key(body))
                && !DRAFTING_NOTE.matcher(proposed).find();
    }

    /** The heading stays in the document; a proposal that repeats it would print it twice. */
    static String withoutHeading(String proposed, String heading) {
        if (proposed == null || heading == null || heading.isBlank()) {
            return proposed;
        }
        var text = proposed.strip();
        var title = heading.strip();
        return text.startsWith(title) ? text.substring(title.length()).strip() : text;
    }

    /**
     * The clause text a replacement deletes: the content after the heading line, plus, for a
     * run-in heading, the clause text that shares the heading's paragraph.
     */
    static String body(String contentText, String headingText, String runInHeading) {
        var content = contentText == null ? "" : contentText.strip();
        var heading = headingText == null ? "" : headingText.strip();
        if (heading.isEmpty() || !content.startsWith(heading)) {
            return content;
        }
        var rest = content.substring(heading.length()).strip();
        if (runInHeading != null && heading.startsWith(runInHeading)) {
            rest = (heading.substring(runInHeading.length()).strip() + "\n" + rest).strip();
        }
        return rest;
    }
}
