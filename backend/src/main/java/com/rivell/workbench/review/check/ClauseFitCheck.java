package com.rivell.workbench.review.check;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.rivell.workbench.intelligence.NoProviderConfiguredException;
import com.rivell.workbench.intelligence.ProviderOutOfCreditException;
import com.rivell.workbench.intelligence.SemanticAnswer;
import com.rivell.workbench.intelligence.SemanticDecisionException;
import com.rivell.workbench.intelligence.SemanticDecisionService;
import com.rivell.workbench.intelligence.SemanticQuestion;
import com.rivell.workbench.intelligence.SpecStateMismatchException;
import com.rivell.workbench.intelligence.UnknownDecisionSpecException;
import com.rivell.workbench.intelligence.YesNoAnswer;

/**
 * One fit check for both lanes (engine decision 2026-10-24 §1 contract C, principle 4): a
 * search-found rule must govern the clause's subject, and clause-library wording must meet the
 * position it serves and cure the clause's gap. Deterministic type and side scope has already run
 * in the structured lookup, so this class never drops a candidate by topic: it asks bounded yes/no
 * questions per candidate, all of one clause's questions in one pack. A wording is asked twice so
 * a partial fit is visible as two reason codes.
 *
 * <p>A bounded decision cannot return free text, so {@code quote} is the clause words the question
 * was asked on (an excerpt), never model prose. A failed or ambiguous answer is UNKNOWN, never
 * FITS: the caller decides what UNKNOWN may still offer, with an honest label.
 */
final class ClauseFitCheck {

    /** A yes/no answer at least this likely is a pass. */
    private static final double FITS_BAND = 0.6;
    /** A yes/no answer at most this likely is a failure; between the bands the answer is a coin flip. */
    private static final double DOES_NOT_FIT_BAND = 0.4;
    /** The clause words kept as the fit quote: enough to see the subject, short enough for a detail. */
    private static final int MAX_QUOTE = 400;

    /** The question asked: does the rule govern the clause, or does the wording meet and cure? */
    enum Kind {
        RULE, WORDING
    }

    enum Verdict {
        FITS, DOES_NOT_FIT, UNKNOWN
    }

    /** Code-owned reason so an eval can count why a verdict came out the way it did. */
    enum Reason {
        GOVERNS,
        DIFFERENT_SUBJECT,
        MEETS_POSITION_YES,
        MEETS_POSITION_NO,
        MEETS_POSITION_UNKNOWN,
        CURES_GAP_YES,
        CURES_GAP_NO,
        CURES_GAP_UNKNOWN,
        EXACT_METADATA_MATCH,
        UNCERTAIN,
        UNAVAILABLE
    }

    /** The clause context every question of one clause shares. */
    record Clause(String ourSide, String contractType, String heading, String clauseText) {
    }

    /**
     * One candidate. {@code key} is the caller's handle for the answers; {@code positionText} is
     * the position a wording must meet (unused for a rule, whose own text is the rule);
     * {@code gap} describes what wording must cure. {@code exactMetadataMatch}: a single rule
     * candidate whose own concept already equals the concept it serves, so the type and side
     * scope having passed, metadata proves its subject without a call. A wording never skips:
     * an exact concept does not prove that it meets the position and cures this gap.
     */
    record Candidate(String key, Kind kind, String candidateText, String positionText,
            String gap, boolean exactMetadataMatch) {
    }

    record Judgment(Verdict verdict, List<Reason> reasons, String quote) {

        Judgment {
            reasons = List.copyOf(reasons);
        }
    }

    record Outcome(Map<String, Judgment> judgments, int questions, int skipped, boolean called,
            UUID executionId) {

        static Outcome none() {
            return new Outcome(Map.of(), 0, 0, false, null);
        }
    }

    private final SemanticDecisionService decisionService;

    ClauseFitCheck(SemanticDecisionService decisionService) {
        this.decisionService = decisionService;
    }

    Outcome judge(Clause clause, List<Candidate> candidates) {
        if (candidates.isEmpty()) {
            return Outcome.none();
        }
        if (candidates.size() == 1 && candidates.getFirst().kind() == Kind.RULE
                && candidates.getFirst().exactMetadataMatch()) {
            var only = candidates.getFirst();
            return new Outcome(Map.of(only.key(),
                    new Judgment(Verdict.FITS, List.of(Reason.EXACT_METADATA_MATCH), null)), 0, 1,
                    false, null);
        }
        var questions = new LinkedHashMap<String, SemanticQuestion>();
        var questionKeys = new LinkedHashMap<String, List<String>>();
        for (int index = 0; index < candidates.size(); index++) {
            var candidate = candidates.get(index);
            if (candidate.kind() == Kind.RULE) {
                var key = key(index);
                questions.put(key, ClauseFitSpecs.ruleQuestion(candidate.candidateText()));
                questionKeys.put(candidate.key(), List.of(key));
            } else {
                var meets = key(index) + "_meets";
                var cures = key(index) + "_cures";
                questions.put(meets, ClauseFitSpecs.wordingMeetsQuestion(
                        candidate.positionText(), candidate.candidateText()));
                questions.put(cures, ClauseFitSpecs.wordingCuresQuestion(
                        candidate.gap(), candidate.candidateText()));
                questionKeys.put(candidate.key(), List.of(meets, cures));
            }
        }
        try {
            var execution = decisionService.evaluate(
                    ClauseFitSpecs.spec(questions),
                    new ClauseFitSpecs.FitState(ClauseFitSpecs.STATE_SCHEMA,
                            clause.contractType(), clause.ourSide(), clause.heading(),
                            clause.clauseText()));
            var judgments = new LinkedHashMap<String, Judgment>();
            for (var candidate : candidates) {
                judgments.put(candidate.key(), judgment(candidate,
                        questionKeys.get(candidate.key()), execution.answers(), clause));
            }
            return new Outcome(Map.copyOf(judgments), questions.size(), 0, true,
                    execution.executionId());
        } catch (ProviderOutOfCreditException e) {
            throw e;
        } catch (SpecStateMismatchException | UnknownDecisionSpecException
                | NoProviderConfiguredException e) {
            // a contract error is a bug, not an answer: fail loudly instead of calling it unchecked
            throw e;
        } catch (SemanticDecisionException e) {
            // the call failed: every candidate is unchecked, and the caller says so
            return unknown(candidates, questions.size());
        }
    }

    private static Outcome unknown(List<Candidate> candidates, int questions) {
        var judgments = new LinkedHashMap<String, Judgment>();
        for (var candidate : candidates) {
            judgments.put(candidate.key(), candidate.kind() == Kind.RULE
                    ? new Judgment(Verdict.UNKNOWN, List.of(Reason.UNAVAILABLE), null)
                    : new Judgment(Verdict.UNKNOWN, List.of(Reason.MEETS_POSITION_UNKNOWN,
                            Reason.CURES_GAP_UNKNOWN, Reason.UNAVAILABLE), null));
        }
        return new Outcome(Map.copyOf(judgments), questions, 0, true, null);
    }

    private static Judgment judgment(Candidate candidate, List<String> keys,
            Map<String, SemanticAnswer> answers, Clause clause) {
        if (candidate.kind() == Kind.RULE) {
            return ruleJudgment(answers.get(keys.getFirst()), clause);
        }
        return wordingJudgment(candidate, answers.get(keys.get(0)), answers.get(keys.get(1)), clause);
    }

    private static Judgment ruleJudgment(SemanticAnswer answer, Clause clause) {
        var probability = probability(answer);
        if (probability == null) {
            return new Judgment(Verdict.UNKNOWN, List.of(Reason.UNAVAILABLE), null);
        }
        if (probability >= FITS_BAND) {
            return new Judgment(Verdict.FITS, List.of(Reason.GOVERNS), null);
        }
        if (probability <= DOES_NOT_FIT_BAND) {
            return new Judgment(Verdict.DOES_NOT_FIT, List.of(Reason.DIFFERENT_SUBJECT),
                    quote(clause.clauseText()));
        }
        return new Judgment(Verdict.UNKNOWN, List.of(Reason.UNCERTAIN), null);
    }

    /**
     * The wording is asked twice. A wording that falls short of the position is replaced; a
     * wording that meets the position but does not cure the gap is only offered as unchecked,
     * so the partial fit is visible and never silently replaced. Both answers are
     * kept apart as reason codes.
     */
    private static Judgment wordingJudgment(Candidate candidate, SemanticAnswer meetsAnswer,
            SemanticAnswer curesAnswer, Clause clause) {
        var meets = outcome(meetsAnswer);
        var cures = outcome(curesAnswer);
        if (meets == Answer.FAIL) {
            return new Judgment(Verdict.DOES_NOT_FIT,
                    List.of(meetsReason(meets), curesReason(cures)),
                    quote(clause.clauseText()));
        }
        if (meets == Answer.PASS && cures == Answer.PASS) {
            return new Judgment(Verdict.FITS,
                    List.of(Reason.MEETS_POSITION_YES, Reason.CURES_GAP_YES), null);
        }
        // meets but does not cure, or an answer that is a coin flip: unchecked, never approved
        return new Judgment(Verdict.UNKNOWN,
                List.of(meetsReason(meets), curesReason(cures)), null);
    }

    private enum Answer {
        PASS, FAIL, UNCERTAIN
    }

    private static Answer outcome(SemanticAnswer answer) {
        var probability = probability(answer);
        if (probability == null) {
            return Answer.UNCERTAIN;
        }
        if (probability >= FITS_BAND) {
            return Answer.PASS;
        }
        if (probability <= DOES_NOT_FIT_BAND) {
            return Answer.FAIL;
        }
        return Answer.UNCERTAIN;
    }

    private static Double probability(SemanticAnswer answer) {
        return answer instanceof YesNoAnswer yesNo ? yesNo.probability() : null;
    }

    private static Reason meetsReason(Answer answer) {
        return switch (answer) {
            case PASS -> Reason.MEETS_POSITION_YES;
            case FAIL -> Reason.MEETS_POSITION_NO;
            case UNCERTAIN -> Reason.MEETS_POSITION_UNKNOWN;
        };
    }

    private static Reason curesReason(Answer answer) {
        return switch (answer) {
            case PASS -> Reason.CURES_GAP_YES;
            case FAIL -> Reason.CURES_GAP_NO;
            case UNCERTAIN -> Reason.CURES_GAP_UNKNOWN;
        };
    }

    static String key(int index) {
        return "fit_" + (index + 1);
    }

    private static String quote(String clauseText) {
        var text = clauseText == null ? "" : clauseText.strip();
        return text.length() <= MAX_QUOTE ? text : text.substring(0, MAX_QUOTE).strip();
    }
}
