package com.rivell.workbench.knowledge;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Deterministic, signal-composing review-risk categorization. No model call, no magic
 * legal-risk score: the category answers how much human scrutiny a candidate requires
 * before publication, from explicit, auditable reasons only.
 */
public class CandidateReviewCategorizer {

    private final SemanticPolicyProperties properties;

    public CandidateReviewCategorizer(SemanticPolicyProperties properties) {
        this.properties = properties;
    }

    public Categorization categorize(KnowledgeCandidate candidate, ArtifactTypeAssessment assessment) {
        var reasons = new LinkedHashSet<ReviewReason>();
        var category = ReviewCategory.ROUTINE;

        for (var signal : candidate.conflictSignals()) {
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
        var primary = candidate.legalConceptKey();
        if (primary == null) {
            reasons.add(ReviewReason.LOW_CLASSIFICATION_CONFIDENCE);
        }
        var positions = candidate.effectivePositions();
        if (candidate.semanticFields().conceptProbabilities().values().stream()
                .noneMatch(probability -> probability >= properties.positionProposal())
                && !positions.isEmpty()) {
            reasons.add(ReviewReason.LOW_CLASSIFICATION_CONFIDENCE);
        }
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
        return new Categorization(category, Set.copyOf(reasons));
    }

    public record Categorization(ReviewCategory category, Set<ReviewReason> reasons) {

        public boolean eligibleForBulkApproval() {
            return category == ReviewCategory.ROUTINE;
        }
    }
}
