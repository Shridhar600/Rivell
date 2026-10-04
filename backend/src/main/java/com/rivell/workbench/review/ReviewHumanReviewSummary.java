package com.rivell.workbench.review;

import java.util.List;

/**
 * Human-resolution state of one review on a list page.
 *
 * <p>Field names and meanings are those of the human-review read model, so a row and
 * {@code GET /api/reviews/{id}/human-review} can never tell a consumer two different
 * stories about the same review. Readiness is the server's own assessment: there is no
 * progress percentage here, because the domain has no such number and inventing one
 * would misrepresent a review that is deliberately blocked.
 *
 * <p>{@code blockers} carries the codes that prevent finalization, so a consumer can tell
 * an unresolved finding apart from an active escalation without inferring either.
 */
public record ReviewHumanReviewSummary(
        String status,
        int actionableFindings,
        int resolvedFindings,
        int remainingFindings,
        int escalated,
        boolean readyToFinalize,
        List<String> blockers) {

    public ReviewHumanReviewSummary {
        blockers = blockers == null ? List.of() : List.copyOf(blockers);
    }
}
