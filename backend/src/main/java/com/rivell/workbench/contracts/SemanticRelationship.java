package com.rivell.workbench.contracts;

import java.util.Objects;
import java.util.UUID;

/**
 * A semantic relationship between two clause regions. Separate from literal
 * cross-references (AD #5.13); feeds cross-clause review in later milestones but
 * is itself only an observed relation, never an acceptability judgment.
 */
public record SemanticRelationship(
        UUID id,
        UUID contractVersionId,
        UUID sourceRegionNodeId,
        UUID targetRegionNodeId,
        RelationshipType type,
        RelationshipDetector detector,
        String basis,
        double probability,
        UUID executionId,
        VerificationState verification,
        UUID verificationExecutionId) {

    public SemanticRelationship {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(contractVersionId, "contractVersionId");
        Objects.requireNonNull(sourceRegionNodeId, "sourceRegionNodeId");
        Objects.requireNonNull(targetRegionNodeId, "targetRegionNodeId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(detector, "detector");
        if (basis == null || basis.isBlank()) {
            throw new IllegalArgumentException("Basis must not be blank");
        }
        if (probability < 0.0 || probability > 1.0) {
            throw new IllegalArgumentException("Probability must be within [0,1]");
        }
    }
}
