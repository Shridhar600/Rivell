package com.rivell.workbench.workflow;

import java.util.List;
import java.util.Objects;

/** Resolver output: readiness/counts plus the executable changes a finalization would freeze. */
public record HumanReviewAssessment(
        HumanReviewProgress progress, List<PlannedChange> plannedChanges) {

    public HumanReviewAssessment {
        Objects.requireNonNull(progress, "progress");
        plannedChanges = plannedChanges == null ? List.of() : List.copyOf(plannedChanges);
    }
}
