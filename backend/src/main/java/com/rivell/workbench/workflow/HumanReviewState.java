package com.rivell.workbench.workflow;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.rivell.workbench.redline.ApprovedChangeSet;
import com.rivell.workbench.review.Finding;
import com.rivell.workbench.review.Review;

/** One review's human-resolution read state (active run findings + current decisions). */
public record HumanReviewState(
        Review review,
        UUID activeRunId,
        List<Finding> findings,
        Map<UUID, HumanDecision> currentDecisions,
        Optional<ApprovedChangeSet> changeSet,
        HumanReviewAssessment assessment) {

    public HumanReviewState {
        Objects.requireNonNull(review, "review");
        findings = findings == null ? List.of() : List.copyOf(findings);
        currentDecisions = currentDecisions == null ? Map.of() : Map.copyOf(currentDecisions);
        Objects.requireNonNull(changeSet, "changeSet");
        Objects.requireNonNull(assessment, "assessment");
    }
}
