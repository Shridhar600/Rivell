package com.rivell.workbench.review;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One typed plan entry: what a review must check. The plan is frozen with the
 * snapshot; execution state lives separately per analysis run.
 */
public record ReviewPlanCheck(
        UUID id,
        UUID organizationId,
        UUID reviewId,
        int ordinal,
        String checkKey,
        CheckFamily family,
        CheckExpectation expectation,
        CheckMechanism mechanism,
        com.rivell.workbench.contracts.LegalConcept concept,
        com.rivell.workbench.contracts.LegalConcept secondaryConcept,
        UUID subjectClauseId,
        Instant createdAt) {

    public ReviewPlanCheck {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(reviewId, "reviewId");
        Objects.requireNonNull(checkKey, "checkKey");
        Objects.requireNonNull(family, "family");
        Objects.requireNonNull(mechanism, "mechanism");
        Objects.requireNonNull(concept, "concept");
        Objects.requireNonNull(createdAt, "createdAt");
        if (family == CheckFamily.CONCEPT_PRESENCE) {
            if (expectation == null) {
                throw new IllegalArgumentException("presence checks require an expectation");
            }
            if (secondaryConcept != null || subjectClauseId != null) {
                throw new IllegalArgumentException("presence checks carry no clause subject");
            }
        }
        if (family == CheckFamily.CLAUSE_REVIEW && subjectClauseId == null) {
            throw new IllegalArgumentException("clause checks require a subject clause");
        }
        if (family == CheckFamily.CROSS_CLAUSE && secondaryConcept == null) {
            throw new IllegalArgumentException("cross-clause checks require a secondary concept");
        }
    }
}
