package com.rivell.workbench.review;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.rivell.workbench.intelligence.ChoiceQuestion;
import com.rivell.workbench.intelligence.DecisionSpec;
import com.rivell.workbench.intelligence.QuestionPack;
import com.rivell.workbench.intelligence.SemanticState;

/** M7 review decision specs: bounded, code-owned, versioned judgments. */
public final class M7DecisionSpecs {

    private M7DecisionSpecs() {
    }

    public static final String CARVE_OUT_CONSISTENCY_SPEC = "m7-carve-out-consistency";
    public static final int CARVE_OUT_CONSISTENCY_VERSION = 1;
    public static final int CARVE_OUT_STATE_SCHEMA = 1;
    public static final String CONSISTENCY = "consistency";
    public static final String CONSISTENT = "CONSISTENT";
    public static final String CAP_CARVEOUT_CONTRADICTS_INDEMNITY = "CAP_CARVEOUT_CONTRADICTS_INDEMNITY";
    public static final String NOT_COMPARABLE_CARVE = "NOT_COMPARABLE";

    /**
     * Contradiction means the two clauses disagree about whether the cap applies to
     * indemnification (e.g. the cap states it applies while the indemnity disclaims
     * all limitations, or the reverse). A carve-out plus an unlimited indemnity is
     * consistent and must not be reported.
     */
    public static DecisionSpec carveOutConsistency() {
        var options = new LinkedHashMap<String, String>();
        options.put(CONSISTENT,
                "The liability carve-out and the indemnification clause can operate together "
                        + "without contradiction.");
        options.put(CAP_CARVEOUT_CONTRADICTS_INDEMNITY,
                "The liability carve-out and the indemnification clause contradict each "
                        + "other (for example the liability cap applies to indemnification "
                        + "while the indemnity clause disclaims all limitations of "
                        + "liability).");
        options.put(NOT_COMPARABLE_CARVE,
                "The interaction cannot be determined from the given clause texts.");
        return new DecisionSpec(
                CARVE_OUT_CONSISTENCY_SPEC,
                CARVE_OUT_CONSISTENCY_VERSION,
                "Decide whether a limitation-of-liability carve-out is semantically "
                        + "consistent with the indemnification clause it interacts with.",
                CARVE_OUT_STATE_SCHEMA,
                new QuestionPack(Map.<String, com.rivell.workbench.intelligence.SemanticQuestion>of(
                        CONSISTENCY, new ChoiceQuestion(
                                "Choose the single best assessment of how the liability "
                                        + "limitation and the indemnification clause interact.",
                                options))));
    }

    public record CarveOutConsistencyState(
            int schemaVersion,
            String liabilityText,
            String indemnityText) implements SemanticState {

        @Override
        public Object payload() {
            return this;
        }
    }

    public static final String FINDING_VERIFICATION_SPEC = "m7-finding-verification";
    public static final int FINDING_VERIFICATION_VERSION = 2;
    public static final int FINDING_VERIFICATION_STATE_SCHEMA = 2;
    public static final String SATISFIES_POLICY = "satisfies_policy";
    public static final String REMOVAL_HURTS_US = "removal_hurts_us";
    public static final String ADDITION_HURTS_US = "addition_hurts_us";

    /**
     * Independent check of machine wording. Code works out which sentences of the clause the
     * candidate removes and which it adds; the decision judges three bounded points from our
     * side: does the candidate meet the position, does a removal take away something that helped
     * us, does an addition hurt us.
     */
    public static DecisionSpec findingVerification() {
        var questions = new LinkedHashMap<String, com.rivell.workbench.intelligence.SemanticQuestion>();
        questions.put(SATISFIES_POLICY, new com.rivell.workbench.intelligence.YesNoQuestion(
                "Does the candidate text meet our playbook position: the preferred position or one "
                        + "of the listed fallbacks, without anything the walk-away forbids? Judge "
                        + "only the points the position deals with.",
                "The candidate meets our preferred position or a listed fallback and avoids the "
                        + "walk-away.",
                "The candidate falls short of every listed fallback, or contains what the "
                        + "walk-away forbids."));
        questions.put(REMOVAL_HURTS_US, new com.rivell.workbench.intelligence.YesNoQuestion(
                "removedFromOriginal lists sentences of the original clause that the candidate no "
                        + "longer contains word for word (some may only be reworded in the "
                        + "candidate). Does the candidate, by leaving any of them out, take away "
                        + "something that helped our side (see ourSide), where the playbook "
                        + "position does not ask for it to go?",
                "Yes: something that protected or benefited our side is gone from the candidate, "
                        + "and the position does not ask for its removal.",
                "No: nothing that helped our side is lost, because the removed text helped the "
                        + "other party, is reworded in the candidate, or the position asks for its "
                        + "removal."));
        questions.put(ADDITION_HURTS_US, new com.rivell.workbench.intelligence.YesNoQuestion(
                "addedByCandidate lists sentences that are new in the candidate. Does any of them "
                        + "add a risk or burden for our side (see ourSide)?",
                "Yes: an added sentence gives the other party a new exception or escape, limits "
                        + "our rights or remedies, or puts a new obligation or cost on us.",
                "No: the added sentences only bind the other party, protect us, or restate what "
                        + "the position asks for."));
        return new DecisionSpec(
                FINDING_VERIFICATION_SPEC, FINDING_VERIFICATION_VERSION,
                "Independently verify machine-proposed clause wording against our playbook "
                        + "position and against what it removes from and adds to the clause.",
                FINDING_VERIFICATION_STATE_SCHEMA,
                new QuestionPack(questions));
    }

    public record FindingVerificationState(
            int schemaVersion, String ourSide, String playbookPosition, String candidateText,
            List<String> removedFromOriginal, List<String> addedByCandidate)
            implements SemanticState {

        @Override
        public Object payload() {
            return this;
        }
    }
}
