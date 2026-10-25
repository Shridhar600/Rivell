package com.rivell.workbench.contracts;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    static final String PARTY_ROLE_SPEC = "contract-party-role";
    static final String FACT_VERIFICATION_SPEC = "contract-fact-verification";
    static final String RELATIONSHIP_SPEC = "contract-relationship";

    private static final int VERSION = 1;

    private M5SemanticSpecs() {
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
        return switch (contractType.family()) {
            case NDA, MNDA, ONDA -> List.of(PartyRole.DISCLOSING_PARTY, PartyRole.RECEIVING_PARTY,
                    PartyRole.PARTY_A, PartyRole.PARTY_B);
            case MSA, SAAS, SOW, DPA, SUPPLY, SERVICES, ORDER_FORM, RESELLER ->
                    List.of(PartyRole.CUSTOMER, PartyRole.SUPPLIER,
                            PartyRole.PARTY_A, PartyRole.PARTY_B);
            case SWL -> List.of(PartyRole.LICENSOR, PartyRole.LICENSEE, PartyRole.CUSTOMER,
                    PartyRole.SUPPLIER, PartyRole.PARTY_A, PartyRole.PARTY_B);
            case AMENDMENT, OTHER -> List.of(PartyRole.CUSTOMER, PartyRole.SUPPLIER,
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
