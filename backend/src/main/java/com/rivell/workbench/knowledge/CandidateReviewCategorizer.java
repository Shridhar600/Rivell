package com.rivell.workbench.knowledge;

import java.util.LinkedHashSet;
import java.util.Set;

import com.rivell.workbench.taxonomy.ConceptKey;
import com.rivell.workbench.taxonomy.ConceptRegistry;

/**
 * Deterministic, signal-composing review-risk categorization. No model call, no magic
 * legal-risk score: the category answers how much human scrutiny a candidate requires
 * before publication, from explicit, auditable reasons only.
 */
public class CandidateReviewCategorizer {

    private final SemanticPolicyProperties properties;
    private final ConceptClassificationProperties conceptProperties;
    private final ConceptRegistry conceptRegistry;

    public CandidateReviewCategorizer(
            SemanticPolicyProperties properties,
            ConceptClassificationProperties conceptProperties,
            ConceptRegistry conceptRegistry) {
        this.properties = properties;
        this.conceptProperties = conceptProperties;
        this.conceptRegistry = conceptRegistry;
    }

    public Categorization categorize(KnowledgeCandidate candidate, ArtifactTypeAssessment assessment) {
        var reasons = new LinkedHashSet<ReviewReason>();
        var category = ReviewCategory.ROUTINE;

        for (var signal : candidate.conflictSignals()) {
            if (signal.conflictType() == ConflictType.DUPLICATE) {
                reasons.add(ReviewReason.DUPLICATE_DETECTED);
            }
            if (signal.conflictType() == ConflictType.CONTRADICTS_EXISTING
                    || signal.conflictType() == ConflictType.AUTHORITY_CONFLICT) {
                reasons.add(ReviewReason.CONFLICT_DETECTED);
            }
            if (signal.conflictType() == ConflictType.SUPERSEDES_EXISTING) {
                reasons.add(ReviewReason.SUPERSESSION_PROPOSED);
            }
            if (signal.conflictType() == ConflictType.UNKNOWN) {
                reasons.add(ReviewReason.SEMANTIC_ANALYSIS_UNAVAILABLE);
            }
        }
        if (assessment != null && assessment.isMismatch()) {
            reasons.add(ReviewReason.ARTIFACT_TYPE_MISMATCH);
        }
        for (var interpretation : candidate.interpretations()) {
            if (interpretation.verification() == VerificationState.DISPUTED) {
                reasons.add(ReviewReason.VERIFICATION_DISPUTED);
            }
            if (interpretation.verification() == VerificationState.UNCERTAIN
                    || interpretation.verification() == VerificationState.NOT_VERIFIED
                            && interpretation.kind() == InterpretationKind.GENERATIVE_EXTRACTION) {
                reasons.add(ReviewReason.UNVERIFIED_GENERATIVE_FIELD);
            }
        }
        var primary = candidate.effectiveConceptKey();
        var belowConceptThreshold = candidate.semanticFields().conceptProbabilities().values().stream()
                .noneMatch(probability -> probability >= conceptProperties.conceptThreshold());
        if (primary == null || belowConceptThreshold && !sourceNamesConcept(candidate, primary)) {
            reasons.add(ReviewReason.LOW_CLASSIFICATION_CONFIDENCE);
        }
        var positions = candidate.effectivePositions();
        if (positions.contains(PositionRole.PROHIBITED) || positions.contains(PositionRole.ESCALATION)) {
            reasons.add(ReviewReason.NEW_PROHIBITED_OR_ESCALATION_POSITION);
        }
        var scopeProposed = candidate.interpretations().stream()
                .anyMatch(interpretation -> interpretation.field().equals("scope"));
        if (scopeProposed) {
            reasons.add(ReviewReason.SCOPE_NARROWING_PROPOSED);
        }

        if (reasons.contains(ReviewReason.CONFLICT_DETECTED)
                || reasons.contains(ReviewReason.SUPERSESSION_PROPOSED)
                || reasons.contains(ReviewReason.VERIFICATION_DISPUTED)) {
            category = ReviewCategory.HIGH;
        } else if (!reasons.isEmpty()) {
            category = ReviewCategory.ELEVATED;
        }
        if (reasons.contains(ReviewReason.DUPLICATE_DETECTED)
                || reasons.contains(ReviewReason.SEMANTIC_COMPILATION_UNAVAILABLE)) {
            category = category == ReviewCategory.HIGH ? ReviewCategory.HIGH : ReviewCategory.ELEVATED;
        }
        return new Categorization(category, Set.copyOf(reasons));
    }

    /**
     * Whether the record's own topic field names the concept it was placed under, by the
     * concept's label or one of its aliases ("IP indemnity" for IP infringement indemnity). The
     * source then agrees with the placement, so a model probability below the concept threshold
     * is no reason to say the entry could not be placed.
     */
    private boolean sourceNamesConcept(KnowledgeCandidate candidate, String conceptKey) {
        var topic = firstTopicValue(candidate);
        if (topic == null) {
            return false;
        }
        try {
            var placed = ConceptKey.of(conceptKey);
            var named = java.util.stream.Stream.concat(
                    conceptRegistry.leaves().stream(), conceptRegistry.parents().stream())
                    .filter(concept -> RecordLabelRoles.normalize(concept.label()).equals(topic)
                            || concept.aliases().stream()
                                    .anyMatch(alias -> RecordLabelRoles.normalize(alias).equals(topic)))
                    .toList();
            // Only a topic unique to the placed concept confirms it. A shared alias
            // ("Suspension of Services") is ambiguous, not agreement.
            return named.size() == 1 && named.getFirst().key().equals(placed);
        } catch (IllegalArgumentException malformedKey) {
            return false;
        }
    }

    private static String firstTopicValue(KnowledgeCandidate candidate) {
        var payload = candidate.effectivePayload();
        for (var key : payload.orderedKeys()) {
            var value = payload.columns().get(key);
            if (value != null && !value.isBlank()
                    && candidate.fieldRole(key).filter(role -> role == RecordFieldRole.TOPIC).isPresent()) {
                return RecordLabelRoles.normalize(value);
            }
        }
        return null;
    }

    public record Categorization(ReviewCategory category, Set<ReviewReason> reasons) {

        public boolean eligibleForBulkApproval() {
            return category == ReviewCategory.ROUTINE;
        }
    }
}
