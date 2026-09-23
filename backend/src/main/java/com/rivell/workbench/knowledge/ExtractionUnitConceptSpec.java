package com.rivell.workbench.knowledge;

import java.util.LinkedHashMap;
import java.util.Map;

import com.rivell.workbench.contracts.LegalConcept;
import com.rivell.workbench.intelligence.DecisionSpec;
import com.rivell.workbench.intelligence.QuestionPack;
import com.rivell.workbench.intelligence.YesNoQuestion;

/**
 * The versioned legal knowledge asset for M3's single production semantic task:
 * which bounded legal concepts an extraction unit expresses.
 *
 * Concepts are NOT mutually exclusive, so each concept is an independent yes/no
 * question over the shared unit state — deliberately not a forced Choice.
 * A changed instruction, concept set, or state schema requires a new spec version.
 */
public record ExtractionUnitConceptSpec() {

    public static final String ID = "extraction-unit-legal-concept";
    public static final int VERSION = 1;

    private static final String TRUE_CRITERION =
            "The text contains an explicit obligation or statement of this legal concept.";
    private static final String FALSE_CRITERION =
            "The text does not contain this legal concept.";

    private static final Map<LegalConcept, String> CRITERIA = Map.of(
            LegalConcept.CONFIDENTIALITY,
            "Confidentiality: obligations to protect another party's confidential information.",
            LegalConcept.TERM_TERMINATION,
            "Term and termination: agreement duration, renewal, or termination rights and mechanics.",
            LegalConcept.LIMITATION_OF_LIABILITY,
            "Limitation of liability: caps, exclusions, or limitations on damages payable.",
            LegalConcept.INDEMNIFICATION,
            "Indemnification: one party compensates the other for losses, claims, or third-party actions.",
            LegalConcept.GOVERNING_LAW,
            "Governing law: the jurisdiction whose law governs the agreement.");

    static DecisionSpec spec() {
        var questions = new LinkedHashMap<String, com.rivell.workbench.intelligence.SemanticQuestion>();
        for (var concept : LegalConcept.values()) {
            questions.put(questionName(concept), new YesNoQuestion(
                    "Does this extraction unit express the legal concept "
                            + concept.name().toLowerCase().replace('_', ' ') + "?",
                    conceptCriterion(concept),
                    FALSE_CRITERION));
        }
        return new DecisionSpec(
                ID, VERSION,
                "Classify an extraction unit against the bounded initial legal-concept vocabulary.",
                ExtractionUnitConceptState.SCHEMA_VERSION,
                new QuestionPack(questions));
    }

    private static String conceptCriterion(LegalConcept concept) {
        var criterion = CRITERIA.get(concept);
        if (criterion == null) {
            throw new IllegalStateException("No criterion defined for concept " + concept);
        }
        return criterion;
    }

    static String questionName(LegalConcept concept) {
        return "concept_" + concept.name().toLowerCase();
    }
}
