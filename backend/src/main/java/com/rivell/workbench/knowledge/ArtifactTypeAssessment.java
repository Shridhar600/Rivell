package com.rivell.workbench.knowledge;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Machine assessment of which artifact class a source most resembles. The human-declared
 * {@link ArtifactType} stays authoritative; this assessment only advises (warning +
 * review risk). State distinguishes agreement, possible mismatch, and honest ambiguity
 * (mixed sources); probability is a provider distribution value, not calibrated legal truth.
 */
public record ArtifactTypeAssessment(
        ArtifactType assessedType,
        double probability,
        AssessmentState state,
        String specId,
        int specVersion,
        java.util.UUID executionId,
        List<String> warnings) {

    public enum AssessmentState {
        MATCH,
        POSSIBLE_MISMATCH,
        AMBIGUOUS
    }

    public ArtifactTypeAssessment {
        Objects.requireNonNull(assessedType);
        Objects.requireNonNull(state);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public boolean isMismatch() {
        return state == AssessmentState.POSSIBLE_MISMATCH;
    }
}
