package com.rivell.workbench.review;

import java.util.LinkedHashMap;
import java.util.Map;

import com.rivell.workbench.intelligence.ChoiceQuestion;
import com.rivell.workbench.intelligence.DecisionSpec;
import com.rivell.workbench.intelligence.QuestionPack;
import com.rivell.workbench.intelligence.SemanticState;

/** M7 review decision specs: bounded, code-owned, versioned judgments. */
public final class M7DecisionSpecs {

    public static final String LIABILITY_DEVIATION_SPEC = "m7-liability-deviation";
    public static final int LIABILITY_DEVIATION_VERSION = 1;
    public static final int LIABILITY_DEVIATION_STATE_SCHEMA = 1;

    public static final String DEVIATION_TYPE = "deviation_type";
    public static final String NO_DEVIATION = "NO_DEVIATION";
    public static final String CAP_ABOVE_PREFERRED = "CAP_ABOVE_PREFERRED";
    public static final String PROHIBITED_STRUCTURE = "PROHIBITED_STRUCTURE";
    public static final String NOT_COMPARABLE = "NOT_COMPARABLE";

    private M7DecisionSpecs() {
    }

    public static DecisionSpec liabilityDeviation() {
        var options = new LinkedHashMap<String, String>();
        options.put(NO_DEVIATION,
                "The limitation is at or within the organization's preferred position.");
        options.put(CAP_ABOVE_PREFERRED,
                "The clause caps liability but above the preferred position.");
        options.put(PROHIBITED_STRUCTURE,
                "The clause contains a structure the policy prohibits, such as uncapped or "
                        + "unlimited liability.");
        options.put(NOT_COMPARABLE,
                "The clause's limitation cannot be compared with the policy position "
                        + "because its structure is indeterminate (for example a cap tied "
                        + "to a non-fee basis or an unknown amount).");
        return new DecisionSpec(
                LIABILITY_DEVIATION_SPEC,
                LIABILITY_DEVIATION_VERSION,
                "Classify how a reviewed limitation-of-liability clause relates to the "
                        + "applicable published policy positions.",
                LIABILITY_DEVIATION_STATE_SCHEMA,
                new QuestionPack(Map.<String, com.rivell.workbench.intelligence.SemanticQuestion>of(
                        DEVIATION_TYPE, new ChoiceQuestion(
                        "Choose the single best classification of the clause against the "
                                + "policy rule text. Uncapped or unlimited liability is a "
                                + "PROHIBITED_STRUCTURE. Use NOT_COMPARABLE only when the "
                                + "clause's structure is genuinely indeterminate.",
                        options))));
    }

    public static final String CARVE_OUT_CONSISTENCY_SPEC = "m7-carve-out-consistency";
    public static final int CARVE_OUT_CONSISTENCY_VERSION = 1;
    public static final int CARVE_OUT_STATE_SCHEMA = 1;
    public static final String CONSISTENCY = "consistency";
    public static final String CONSISTENT = "CONSISTENT";
    public static final String CAP_CARVEOUT_CONTRADICTS_INDEMNITY = "CAP_CARVEOUT_CONTRADICTS_INDEMNITY";
    public static final String NOT_COMPARABLE_CARVE = "NOT_COMPARABLE";

    public static DecisionSpec carveOutConsistency() {
        var options = new LinkedHashMap<String, String>();
        options.put(CONSISTENT,
                "The liability carve-out and the indemnification clause can operate together "
                        + "without contradiction.");
        options.put(CAP_CARVEOUT_CONTRADICTS_INDEMNITY,
                "The liability carve-out and the indemnification clause contradict each "
                        + "other (for example the cap excludes indemnity while indemnity "
                        + "disclaims all limitations).");
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
    public static final int FINDING_VERIFICATION_VERSION = 1;
    public static final int FINDING_VERIFICATION_STATE_SCHEMA = 1;
    public static final String SATISFIES_POLICY = "satisfies_policy";
    public static final String INTRODUCED_NEW_RISK = "introduced_new_risk";

    public static DecisionSpec findingVerification() {
        var questions = new LinkedHashMap<String, com.rivell.workbench.intelligence.SemanticQuestion>();
        questions.put(SATISFIES_POLICY, new com.rivell.workbench.intelligence.YesNoQuestion(
                "Does the candidate language satisfy the stated policy expectation?",
                "The candidate language satisfies the policy expectation.",
                "The candidate language does not satisfy the policy expectation."));
        questions.put(INTRODUCED_NEW_RISK, new com.rivell.workbench.intelligence.YesNoQuestion(
                "Does the candidate language introduce a new risk not present in the original clause?",
                "The candidate introduces a new risk (for example a new exception, cap, "
                        + "or obligation shift).",
                "The candidate introduces no new risk beyond addressing the expectation."));
        return new DecisionSpec(
                FINDING_VERIFICATION_SPEC, FINDING_VERIFICATION_VERSION,
                "Independently verify a machine-proposed candidate clause against the "
                        + "policy expectation and the original clause.",
                FINDING_VERIFICATION_STATE_SCHEMA,
                new QuestionPack(questions));
    }

    public record FindingVerificationState(
            int schemaVersion, String proposedLanguage, String policyText, String clauseText)
            implements SemanticState {

        @Override
        public Object payload() {
            return this;
        }
    }

    public record LiabilityDeviationState(
            int schemaVersion,
            String clauseText,
            String capMultiple,
            String capBasis,
            Boolean consequentialDamagesExcluded,
            String preferredPosition,
            String prohibitedPosition,
            String escalationTrigger) implements SemanticState {

        @Override
        public Object payload() {
            return this;
        }
    }
}
