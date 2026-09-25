package com.rivell.workbench.review.check;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.rivell.workbench.review.CheckExpectation;
import com.rivell.workbench.review.CheckMechanism;
import com.rivell.workbench.review.FindingSubjectKind;
import com.rivell.workbench.review.IssueFamily;
import com.rivell.workbench.review.PresencePayload;
import com.rivell.workbench.review.RawReviewSignal;
import com.rivell.workbench.review.SignalType;

/**
 * Concept-presence check: absence of REQUIRED/EXPECTED concepts produces bounded
 * absence-subject signals; OPTIONAL absence creates nothing.
 */
public final class PresenceCheckExecutor implements ReviewCheckExecutor {

    @Override
    public CheckMechanism mechanism() {
        return CheckMechanism.PRESENCE;
    }

    @Override
    public CheckOutcome execute(ReviewCheckContext context) {
        var check = context.check();
        var present = context.semantics().clauses().stream().anyMatch(clause ->
                clause.effectivePrimaryConcept()
                        .filter(concept -> concept == check.concept())
                        .isPresent());
        if (present) {
            return CheckOutcome.completed(List.of(), Map.of("present", true));
        }
        return switch (check.expectation()) {
            case REQUIRED -> CheckOutcome.completed(
                    List.of(signal(context, SignalType.MISSING_REQUIRED_CONCEPT,
                            IssueFamily.MISSING_REQUIRED_PROVISION)),
                    Map.of("present", false, "expectation", CheckExpectation.REQUIRED.name()));
            case EXPECTED -> CheckOutcome.completed(
                    List.of(signal(context, SignalType.EXPECTED_CONCEPT_ABSENT,
                            IssueFamily.EXPECTED_PROVISION_ABSENT)),
                    Map.of("present", false, "expectation", CheckExpectation.EXPECTED.name()));
            case OPTIONAL -> CheckOutcome.completed(
                    List.of(),
                    Map.of("present", false, "expectation", CheckExpectation.OPTIONAL.name()));
        };
    }

    private static RawReviewSignal signal(
            ReviewCheckContext context, SignalType type, IssueFamily issueFamily) {
        var check = context.check();
        return new RawReviewSignal(
                UUID.randomUUID(), check.organizationId(), check.reviewId(),
                context.runId(), check.id(), type, FindingSubjectKind.ABSENCE, null,
                check.concept(), check.concept(), null, issueFamily,
                new PresencePayload(check.expectation()),
                List.of(), null, null, null, null, null,
                Map.of("expectation", check.expectation().name()),
                "PRESENCE:" + check.concept(), context.executedAt());
    }
}
