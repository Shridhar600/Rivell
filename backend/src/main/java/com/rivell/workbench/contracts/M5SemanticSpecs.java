package com.rivell.workbench.contracts;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.rivell.workbench.intelligence.ChoiceQuestion;
import com.rivell.workbench.intelligence.DecisionSpec;
import com.rivell.workbench.intelligence.QuestionPack;
import com.rivell.workbench.intelligence.SemanticQuestion;
import com.rivell.workbench.intelligence.YesNoQuestion;

/**
 * M5 semantic decision specs: bounded, code-owned, versioned, contracts-owned.
 * Each spec defines one bounded judgment over minimal state; changed wording or
 * criteria require a version bump. Thresholds live in ContractEnrichmentProperties.
 */
final class M5SemanticSpecs {

    static final String CLAUSE_CONCEPT_SPEC = "contract-clause-legal-concept";
    static final String PRIMARY_CONCEPT_SPEC = "contract-primary-concept";
    static final String PARTY_ROLE_SPEC = "contract-party-role";
    static final String GOVERNING_LAW_SPEC = "contract-metadata-governing-law";
    static final String FACT_VERIFICATION_SPEC = "contract-fact-verification";
    static final String RELATIONSHIP_SPEC = "contract-relationship";

    private static final int VERSION = 1;

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

    private M5SemanticSpecs() {
    }

    static DecisionSpec clauseConcepts() {
        var questions = new LinkedHashMap<String, SemanticQuestion>();
        for (var concept : LegalConcept.values()) {
            questions.put(conceptQuestionName(concept), new YesNoQuestion(
                    "Does this contract clause express the legal concept "
                            + concept.name().toLowerCase().replace('_', ' ') + "?",
                    CRITERIA.get(concept),
                    "The clause does not express this legal concept."));
        }
        return new DecisionSpec(
                CLAUSE_CONCEPT_SPEC, VERSION,
                "Classify a contract clause region against the bounded initial legal-concept "
                        + "vocabulary.",
                M5SemanticStates.ClauseState.SCHEMA_VERSION,
                new QuestionPack(questions));
    }

    static DecisionSpec primaryConcept(List<LegalConcept> members) {
        var criteria = new LinkedHashMap<String, String>();
        for (var member : members) {
            criteria.put(member.name(), CRITERIA.get(member));
        }
        return new DecisionSpec(
                PRIMARY_CONCEPT_SPEC, VERSION,
                "Choose which of the identified concepts is the primary subject of the clause.",
                M5SemanticStates.PrimaryConceptState.SCHEMA_VERSION,
                new QuestionPack(Map.of("primary_concept", new ChoiceQuestion(
                        "Which single concept is the primary subject of this clause? "
                                + "The clause may also express the others secondarily.",
                        criteria))));
    }

    static DecisionSpec partyRoles(List<PartyRole> roles) {
        var questions = new LinkedHashMap<String, SemanticQuestion>();
        for (var role : roles) {
            questions.put(roleQuestionName(role), new YesNoQuestion(
                    "Does this party act as the " + role.name().toLowerCase().replace('_', ' ')
                            + " in this agreement?",
                    "The party plainly acts as the "
                            + role.name().toLowerCase().replace('_', ' ')
                            + " in the agreement context.",
                    "The party does not act as the "
                            + role.name().toLowerCase().replace('_', ' ') + "."));
        }
        return new DecisionSpec(
                PARTY_ROLE_SPEC, VERSION,
                "Classify the roles a party holds in this agreement, informed by the "
                        + "declared review profile.",
                M5SemanticStates.PartyRoleState.SCHEMA_VERSION,
                new QuestionPack(questions));
    }

    static DecisionSpec governingLaw() {
        var criteria = new LinkedHashMap<String, String>();
        criteria.put("US", "The governing law is a jurisdiction of the United States.");
        criteria.put("UK", "The governing law is a jurisdiction of the United Kingdom "
                + "(England and Wales, Scotland, or Northern Ireland).");
        criteria.put("UNSPECIFIED", "No governing-law jurisdiction is stated, or it is "
                + "neither clearly US nor UK.");
        return new DecisionSpec(
                GOVERNING_LAW_SPEC, VERSION,
                "Identify the governing jurisdiction named by the contract.",
                M5SemanticStates.ClauseState.SCHEMA_VERSION,
                new QuestionPack(Map.of("governing_law", new ChoiceQuestion(
                        "Which jurisdiction's law governs this agreement?", criteria))));
    }

    static DecisionSpec factVerification() {
        return new DecisionSpec(
                FACT_VERIFICATION_SPEC, VERSION,
                "Independently verify an extracted fact against the exact clause text.",
                M5SemanticStates.FactVerificationState.SCHEMA_VERSION,
                new QuestionPack(Map.of("source_supports", new YesNoQuestion(
                        "Does the clause text explicitly state the following claim?",
                        "The clause text explicitly states the claim.",
                        "The clause text does not state the claim."))));
    }

    static DecisionSpec relationships(List<RelationshipType> types) {
        var questions = new LinkedHashMap<String, SemanticQuestion>();
        for (var type : types) {
            questions.put(relationshipQuestionName(type), new YesNoQuestion(
                    relationshipQuestion(type),
                    "The source clause plainly " + relationshipPhrase(type) + " the target clause.",
                    "The source clause does not " + relationshipPhrase(type)
                            + " the target clause."));
        }
        return new DecisionSpec(
                RELATIONSHIP_SPEC, VERSION,
                "Decide which semantic relationships hold between two narrowed clauses.",
                M5SemanticStates.RelationshipState.SCHEMA_VERSION,
                new QuestionPack(questions));
    }

    static String conceptQuestionName(LegalConcept concept) {
        return "concept_" + concept.name().toLowerCase();
    }

    static String roleQuestionName(PartyRole role) {
        return "role_" + role.name().toLowerCase();
    }

    static String relationshipQuestionName(RelationshipType type) {
        return "rel_" + type.name().toLowerCase();
    }

    static List<RelationshipType> relationshipTypes() {
        return List.of(RelationshipType.DEPENDS_ON, RelationshipType.MODIFIES,
                RelationshipType.CARVES_OUT, RelationshipType.SURVIVES);
    }

    static List<PartyRole> partyRoleSet(ContractType contractType) {
        return switch (contractType) {
            case NDA -> List.of(PartyRole.DISCLOSING_PARTY, PartyRole.RECEIVING_PARTY,
                    PartyRole.PARTY_A, PartyRole.PARTY_B);
            case MSA -> List.of(PartyRole.CUSTOMER, PartyRole.SUPPLIER,
                    PartyRole.PARTY_A, PartyRole.PARTY_B);
            case OTHER -> List.of(PartyRole.CUSTOMER, PartyRole.SUPPLIER,
                    PartyRole.DISCLOSING_PARTY, PartyRole.RECEIVING_PARTY,
                    PartyRole.PARTY_A, PartyRole.PARTY_B);
        };
    }

    private static String relationshipQuestion(RelationshipType type) {
        return "Considering only the two clauses quoted, does the source clause "
                + relationshipPhrase(type) + " the target clause?";
    }

    private static String relationshipPhrase(RelationshipType type) {
        return switch (type) {
            case DEPENDS_ON -> "depend on or defer to";
            case MODIFIES -> "modify or amend";
            case CARVES_OUT -> "carve out an exception from";
            case SURVIVES -> "continue to apply after the termination described in";
        };
    }
}
