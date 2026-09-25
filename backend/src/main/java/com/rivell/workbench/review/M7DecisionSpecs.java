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
                "The clause's limitation cannot be meaningfully compared with the policy "
                        + "position.");
        return new DecisionSpec(
                LIABILITY_DEVIATION_SPEC,
                LIABILITY_DEVIATION_VERSION,
                "Classify how a reviewed limitation-of-liability clause relates to the "
                        + "applicable published policy positions.",
                LIABILITY_DEVIATION_STATE_SCHEMA,
                new QuestionPack(Map.<String, com.rivell.workbench.intelligence.SemanticQuestion>of(
                        DEVIATION_TYPE, new ChoiceQuestion(
                        "Choose the single best classification of the clause against the "
                                + "policy. Use NOT_COMPARABLE when the clause's structure "
                                + "cannot be compared (for example no monetary cap or a "
                                + "non-fee basis).",
                        options))));
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
