package com.rivell.workbench.review.check;

import java.util.Map;

import com.rivell.workbench.intelligence.DecisionSpec;
import com.rivell.workbench.intelligence.QuestionPack;
import com.rivell.workbench.intelligence.SemanticQuestion;
import com.rivell.workbench.intelligence.SemanticState;
import com.rivell.workbench.intelligence.YesNoQuestion;

/**
 * The {@code clause-fit} decision (engine decision 2026-10-24 §1 contract C, principle 4): one
 * bounded yes/no per question, all of one clause's questions in one pack. The question text lives
 * here with the version, so a changed prompt is a new version. A wording is asked twice, so a
 * partial fit (meets the position but does not cure the gap, or the reverse) is visible to the
 * scorer; the person still sees one verdict label.
 */
public final class ClauseFitSpecs {

    static final String SPEC = "clause-fit";
    static final int VERSION = 1;
    static final int STATE_SCHEMA = 1;

    private ClauseFitSpecs() {
    }

    static DecisionSpec spec(Map<String, SemanticQuestion> questions) {
        return new DecisionSpec(
                SPEC, VERSION,
                "Decide whether a playbook rule governs one clause's subject, and whether "
                        + "clause-library wording meets the position it serves and cures the "
                        + "clause's gap. Independent yes/no answers; code owns the outcome and "
                        + "never treats an unanswered question as a pass.",
                STATE_SCHEMA,
                new QuestionPack(questions));
    }

    static SemanticQuestion ruleQuestion(String rule) {
        return new YesNoQuestion(
                "Does this playbook rule govern the subject of this clause?\nRule: " + rule,
                "The rule addresses this clause's subject: it states our stance on what the "
                        + "clause does, even where the clause departs from it.",
                "The rule addresses a different subject: it is written for another kind of "
                        + "clause, so judging this clause against it would test the wrong rule.");
    }

    static SemanticQuestion wordingMeetsQuestion(String position, String wording) {
        return new YesNoQuestion(
                "Does this clause-library wording meet the playbook position it serves?"
                        + "\nPosition: " + position
                        + "\nWording: " + wording,
                "The wording meets the position: the preferred position or a listed fallback, "
                        + "with nothing the walk-away forbids.",
                "The wording falls short of the position: it is below every listed fallback, or "
                        + "it contains what the walk-away forbids.");
    }

    static SemanticQuestion wordingCuresQuestion(String gap, String wording) {
        return new YesNoQuestion(
                "Read in place of this clause, does the clause-library wording cure the clause's "
                        + "gap?\nGap: " + gap
                        + "\nWording: " + wording,
                "The wording, read in place of the clause, cures the gap: what the position "
                        + "forbids or leaves worse than a fallback is gone.",
                "The wording leaves this clause's gap uncured: it addresses a different point, or "
                        + "it keeps what the position forbids.");
    }

    /**
     * The clause the questions are asked about; the candidates travel in the questions. Public so
     * a scripted provider can see which clause a fit call belongs to.
     */
    public record FitState(int schemaVersion, String contractType, String ourSide, String heading,
            String clause) implements SemanticState {

        @Override
        public Object payload() {
            return this;
        }
    }
}
