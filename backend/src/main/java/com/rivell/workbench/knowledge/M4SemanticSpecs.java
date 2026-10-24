package com.rivell.workbench.knowledge;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

import com.rivell.workbench.intelligence.ChoiceQuestion;
import com.rivell.workbench.intelligence.DecisionSpec;
import com.rivell.workbench.intelligence.QuestionPack;
import com.rivell.workbench.intelligence.SemanticQuestion;
import com.rivell.workbench.intelligence.YesNoQuestion;

/**
 * M4 semantic decision specs: bounded, code-owned, versioned. Each spec defines one
 * bounded judgment over minimal state; changed wording or criteria require a version
 * bump. Thresholds live in SemanticPolicyProperties, never in the specs.
 */
final class M4SemanticSpecs {

    static final String ARTIFACT_TYPE_SPEC = "artifact-type-assessment";
    static final String PLAYBOOK_POSITION_SPEC = "playbook-position";
    static final String PLAYBOOK_APPROVAL_SPEC = "playbook-approval";
    static final String CLAUSE_VARIANT_ROLE_SPEC = "clause-variant-role";
    static final String SCOPE_NARROWING_SPEC = "scope-narrowing";
    static final String CONFLICT_RELATIONSHIP_SPEC = "conflict-relationship";
    static final String FIELD_VERIFICATION_SPEC = "field-verification";

    private static final int VERSION = 1;
    /** The known-jurisdiction criteria widened beyond US and UK. */
    private static final int SCOPE_NARROWING_VERSION = 2;
    /**
     * v2 asks only the stances the source does not state, and asks for approval only when the
     * source states no typed approval rule.
     */
    static final int PLAYBOOK_POSITION_VERSION = 2;

    private M4SemanticSpecs() {
    }

    static DecisionSpec artifactTypeAssessment() {
        var criteria = new LinkedHashMap<String, String>();
        for (var type : ArtifactType.values()) {
            criteria.put(type.name(), typeDescription(type));
        }
        var pack = new QuestionPack(Map.of(
                "artifact_class", new ChoiceQuestion(
                        "Which knowledge artifact class does this source most resemble?",
                        criteria)));
        return new DecisionSpec(
                ARTIFACT_TYPE_SPEC, VERSION,
                "Assess which knowledge artifact class the source structurally and semantically resembles.",
                M4SemanticStates.ArtifactAssessmentState.SCHEMA_VERSION,
                pack);
    }

    static DecisionSpec playbookPositions() {
        return playbookPositions(new java.util.LinkedHashSet<>(playbookRoles()), true);
    }

    /**
     * The same decision over only the stances the source does not state, so Jev answers what the
     * text leaves open. {@code askApproval} is false when the stage already found a typed
     * approval rule; a label the source states is never asked again.
     */
    static DecisionSpec playbookPositions(java.util.Set<PositionRole> roles, boolean askApproval) {
        var questions = new LinkedHashMap<String, SemanticQuestion>();
        for (var role : roles) {
            questions.put(positionQuestionName(role), new YesNoQuestion(
                    "Does this playbook guidance express a " + role.name().toLowerCase()
                            + " position?",
                    roleCriterion(role),
                    "The guidance does not express a " + role.name().toLowerCase() + " position."));
        }
        if (askApproval) {
            questions.put("requires_approval", new YesNoQuestion(
                    "Does this playbook guidance require a named human approval (such as general counsel) "
                            + "before the position may be used?",
                    "The guidance explicitly requires approval by a named role before use.",
                    "No explicit approval requirement is stated."));
        }
        return new DecisionSpec(
                PLAYBOOK_POSITION_SPEC, PLAYBOOK_POSITION_VERSION,
                "Classify the negotiation position roles a playbook guidance unit expresses.",
                M4SemanticStates.PlaybookUnitState.SCHEMA_VERSION,
                new QuestionPack(questions));
    }

    static DecisionSpec clauseVariantRole() {
        var criteria = new LinkedHashMap<String, String>();
        for (var role : PositionRole.values()) {
            criteria.put(role.name(),
                    role == PositionRole.UNSPECIFIED
                            ? "The clause variant carries no explicit negotiation position label."
                            : roleCriterion(role));
        }
        return new DecisionSpec(
                CLAUSE_VARIANT_ROLE_SPEC, VERSION,
                "Classify the negotiation role of one approved clause variant.",
                M4SemanticStates.PlaybookUnitState.SCHEMA_VERSION,
                new QuestionPack(Map.of(
                        "variant_role", new ChoiceQuestion(
                                "Which single negotiation position role does this approved clause variant carry?",
                                criteria))));
    }

    static DecisionSpec scopeNarrowing() {
        var jurisdictionCriteria = new LinkedHashMap<String, String>();
        for (var jurisdiction : KnownJurisdiction.values()) {
            jurisdictionCriteria.put(jurisdiction.name(), jurisdiction.description());
        }
        return new DecisionSpec(
                SCOPE_NARROWING_SPEC, SCOPE_NARROWING_VERSION,
                "Determine whether a source unit narrows applicability compared to the manifest "
                        + "and, if so, which known jurisdiction it names.",
                M4SemanticStates.PlaybookUnitState.SCHEMA_VERSION,
                new QuestionPack(Map.of(
                        "narrows_scope", new YesNoQuestion(
                                "Does this unit explicitly narrow the applicability of the guidance "
                                        + "below the artifact's manifest scope (for example to one jurisdiction, "
                                        + "one paper source, or one contract type)?",
                                "The unit text states an explicit, narrower applicability condition.",
                                "No narrower applicability condition is stated."),
                        "jurisdiction", new ChoiceQuestion(
                                "Which jurisdiction does this unit name for its applicability, if any?",
                                jurisdictionCriteria))));
    }

    static DecisionSpec fieldVerification() {
        return new DecisionSpec(
                FIELD_VERIFICATION_SPEC, VERSION,
                "Independently verify a generatively extracted field against the exact source text.",
                M4SemanticStates.FieldVerificationState.SCHEMA_VERSION,
                new QuestionPack(Map.of(
                        "source_supports", new YesNoQuestion(
                                "Does the source text explicitly state the following claim?",
                                "The source text explicitly states the claim.",
                                "The source text does not state the claim."))));
    }

    static DecisionSpec conflictRelationship() {
        return new DecisionSpec(
                CONFLICT_RELATIONSHIP_SPEC, VERSION,
                "Compare one new candidate against narrowed published peers for material contradiction "
                        + "and supersession intent.",
                M4SemanticStates.ConflictPairState.SCHEMA_VERSION,
                new QuestionPack(Map.of(
                        "contradicts", new YesNoQuestion(
                                "Do the two positions materially contradict each other, meaning both cannot "
                                        + "reasonably be followed in the same situation?",
                                "The two positions materially contradict each other.",
                                "The two positions do not materially contradict each other."),
                        "supersedes", new YesNoQuestion(
                                "Does the newer position appear intended to replace the older one "
                                        + "(stated replacement, later effective date, or explicit update language)?",
                                "The newer position appears intended to replace the older one.",
                                "There is no stated or implied intent to replace the older one."))));
    }

    static String positionQuestionName(PositionRole role) {
        return "role_" + role.name().toLowerCase();
    }

    static List<PositionRole> playbookRoles() {
        return List.of(PositionRole.PREFERRED, PositionRole.FALLBACK,
                PositionRole.PROHIBITED, PositionRole.ESCALATION, PositionRole.COMMENTARY);
    }

    static String peerQuestionName(int index, String relationship) {
        return relationship + "_peer_" + index;
    }

    private static String roleCriterion(PositionRole role) {
        return switch (role) {
            case PREFERRED -> "The guidance states the organization's first-choice position to pursue in negotiation.";
            case FALLBACK -> "The guidance states an acceptable second-choice position.";
            case PROHIBITED -> "The guidance states a position the organization must not accept.";
            case ESCALATION -> "The guidance defines when or how the matter must be escalated (for example to general counsel).";
            case COMMENTARY -> "The guidance explains context, rationale, or negotiation commentary rather than a position.";
            case UNSPECIFIED -> "No explicit position role.";
        };
    }

    private static String typeDescription(ArtifactType type) {
        return switch (type) {
            case PLAYBOOK -> "Internal negotiation guidance: preferred, fallback, prohibited positions, escalation rules.";
            case CLAUSE_LIBRARY -> "Approved reusable clause variants organized for reuse in drafting.";
            case TEMPLATE -> "A reusable document template with placeholder structure.";
            case EXECUTED_PRECEDENT -> "An executed agreement between named parties that was actually signed.";
            case NEGOTIATION_HISTORY -> "Historical negotiation records, redlines, or positions taken in past deals.";
            case EXTERNAL_REFERENCE -> "Reference material from outside the organization.";
        };
    }
}
