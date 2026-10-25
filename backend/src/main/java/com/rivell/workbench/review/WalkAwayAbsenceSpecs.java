package com.rivell.workbench.review;

import java.util.LinkedHashMap;
import java.util.List;

import com.rivell.workbench.intelligence.DecisionSpec;
import com.rivell.workbench.intelligence.QuestionPack;
import com.rivell.workbench.intelligence.SemanticQuestion;
import com.rivell.workbench.intelligence.SemanticState;
import com.rivell.workbench.intelligence.YesNoQuestion;

/**
 * The {@code walk-away-absence} decision (R3-002): does a published position's walk-away
 * describe the absence of a right or protection ("No termination for convenience"), rather
 * than wording we would reject? One yes/no question per candidate, keyed
 * {@code walk_away_0..n} so code maps answers back by index.
 */
public final class WalkAwayAbsenceSpecs {

    public static final String WALK_AWAY_ABSENCE_SPEC = "walk-away-absence";
    public static final int WALK_AWAY_ABSENCE_VERSION = 1;
    public static final int STATE_SCHEMA_VERSION = 1;
    static final String KEY_PREFIX = "walk_away_";

    private WalkAwayAbsenceSpecs() {
    }

    public static DecisionSpec spec(List<String> walkAwayTexts) {
        var questions = new LinkedHashMap<String, SemanticQuestion>();
        for (int index = 0; index < walkAwayTexts.size(); index++) {
            questions.put(key(index), new YesNoQuestion(
                    "Does this walk-away describe the absence of a right or protection "
                            + "(for example 'No termination for convenience'), rather than "
                            + "unacceptable wording? The walk-away: " + walkAwayTexts.get(index),
                    "The walk-away states that a right or protection we require is not granted, "
                            + "so a contract that does not grant it matches the walk-away.",
                    "The walk-away describes wording we would reject, not the absence of a "
                            + "right or protection."));
        }
        return new DecisionSpec(WALK_AWAY_ABSENCE_SPEC, WALK_AWAY_ABSENCE_VERSION,
                "Judge whether a published playbook position's walk-away describes a right "
                        + "or protection the contract must grant.",
                STATE_SCHEMA_VERSION, new QuestionPack(questions));
    }

    public static String key(int index) {
        return KEY_PREFIX + index;
    }

    /** The slice of the review the walk-away absence decision needs. */
    public record AbsenceState(int schemaVersion, String contractType, String ourSide)
            implements SemanticState {

        @Override
        public Object payload() {
            return this;
        }
    }
}
