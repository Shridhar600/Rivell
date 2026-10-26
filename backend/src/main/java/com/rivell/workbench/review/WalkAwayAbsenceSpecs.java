package com.rivell.workbench.review;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;

import com.rivell.workbench.intelligence.DecisionSpec;
import com.rivell.workbench.intelligence.QuestionPack;
import com.rivell.workbench.intelligence.SemanticQuestion;
import com.rivell.workbench.intelligence.SemanticState;
import com.rivell.workbench.intelligence.YesNoQuestion;

/**
 * The {@code walk-away-absence} decision: does a published position's walk-away
 * describe the absence of a right or protection ("No termination for convenience"), rather
 * than wording we would reject? A walk-away often lists several cases ("No termination for
 * convenience, or a termination payment that compensates lost profit"); asked as one question,
 * a list that mixes an absence with bad wording gets a hedged answer. So each case is asked on
 * its own, keyed {@code walk_away_<candidate>_<case>}, and the candidate takes its highest case.
 */
public final class WalkAwayAbsenceSpecs {

    public static final String WALK_AWAY_ABSENCE_SPEC = "walk-away-absence";
    public static final int WALK_AWAY_ABSENCE_VERSION = 2;
    public static final int STATE_SCHEMA_VERSION = 1;
    static final String KEY_PREFIX = "walk_away_";
    /** Cases split at a sentence end, at ", or", or at a semicolon; a bare "or" stays inside. */
    private static final Pattern CASE_BOUNDARY =
            Pattern.compile("\\.\\s+(?=\\p{Lu})|,\\s*or\\s+|;\\s*(?:or\\s+)?");
    private static final Pattern LEADING_ALSO = Pattern.compile("^(?i)also\\s+");

    private WalkAwayAbsenceSpecs() {
    }

    /** The cases one walk-away lists, in order; the whole text when it lists one. */
    public static List<String> cases(String walkAway) {
        var cases = new ArrayList<String>();
        for (var part : CASE_BOUNDARY.split(walkAway.strip())) {
            var text = LEADING_ALSO.matcher(part.strip()).replaceFirst("");
            text = text.endsWith(".") ? text.substring(0, text.length() - 1).strip() : text;
            if (!text.isEmpty()) {
                cases.add(text);
            }
        }
        return cases.isEmpty() ? List.of(walkAway.strip()) : List.copyOf(cases);
    }

    /** One question per case of each candidate's walk-away, from {@link #cases}. */
    public static DecisionSpec spec(List<List<String>> casesByCandidate) {
        var questions = new LinkedHashMap<String, SemanticQuestion>();
        for (int candidate = 0; candidate < casesByCandidate.size(); candidate++) {
            var cases = casesByCandidate.get(candidate);
            for (int index = 0; index < cases.size(); index++) {
                questions.put(key(candidate, index), question(cases.get(index)));
            }
        }
        return new DecisionSpec(WALK_AWAY_ABSENCE_SPEC, WALK_AWAY_ABSENCE_VERSION,
                "Judge whether a published playbook position's walk-away describes a right "
                        + "or protection the contract must grant.",
                STATE_SCHEMA_VERSION, new QuestionPack(questions));
    }

    private static YesNoQuestion question(String walkAwayCase) {
        return new YesNoQuestion(
                "Does this walk-away describe the absence of a right or protection "
                        + "(for example 'No termination for convenience'), rather than "
                        + "unacceptable wording? The walk-away: " + walkAwayCase,
                "The walk-away states that a right or protection we require is not granted, "
                        + "so a contract that does not grant it matches the walk-away.",
                "The walk-away describes wording we would reject, not the absence of a "
                        + "right or protection.");
    }

    public static String key(int candidate, int walkAwayCase) {
        return KEY_PREFIX + candidate + "_" + walkAwayCase;
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
