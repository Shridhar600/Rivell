package com.rivell.workbench.review;

import java.util.LinkedHashMap;
import java.util.List;

import com.rivell.workbench.intelligence.DecisionSpec;
import com.rivell.workbench.intelligence.QuestionPack;
import com.rivell.workbench.intelligence.SemanticQuestion;
import com.rivell.workbench.intelligence.SemanticState;
import com.rivell.workbench.intelligence.YesNoQuestion;

/**
 * The {@code position-requires-presence} decision (engine v2 P4, design D6): does a
 * published playbook position require the contract to contain a provision on its subject,
 * rather than only governing such a provision if one exists? One yes/no question per
 * candidate position, keyed {@code presence_0..n} so code maps answers back by index.
 */
public final class PresenceSpecs {

    public static final String PRESENCE_SPEC = "position-requires-presence";
    public static final int PRESENCE_VERSION = 1;
    public static final int STATE_SCHEMA_VERSION = 1;
    static final String KEY_PREFIX = "presence_";

    private PresenceSpecs() {
    }

    public static DecisionSpec spec(List<String> positionTexts) {
        var questions = new LinkedHashMap<String, SemanticQuestion>();
        for (int index = 0; index < positionTexts.size(); index++) {
            questions.put(key(index), new YesNoQuestion(
                    "Does this playbook position require the contract to contain a provision "
                            + "on this subject, rather than only governing such a provision if "
                            + "one exists? The position: " + positionTexts.get(index),
                    "The position requires the contract to contain a provision on its subject: "
                            + "a contract without one breaches the position.",
                    "The position only governs a provision if one exists, or the contract need "
                            + "not contain a provision on its subject."));
        }
        return new DecisionSpec(PRESENCE_SPEC, PRESENCE_VERSION,
                "Judge whether a published playbook position requires a provision the contract "
                        + "must contain.",
                STATE_SCHEMA_VERSION, new QuestionPack(questions));
    }

    public static String key(int index) {
        return KEY_PREFIX + index;
    }

    /** The slice of the review the presence decision needs. */
    public record PresenceState(int schemaVersion, String contractType, String ourSide)
            implements SemanticState {

        @Override
        public Object payload() {
            return this;
        }
    }
}
