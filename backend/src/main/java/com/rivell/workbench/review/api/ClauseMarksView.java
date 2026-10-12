package com.rivell.workbench.review.api;

import java.util.List;
import java.util.UUID;

import com.rivell.workbench.review.ClauseMark;

/**
 * One mark per planned clause of the active run, in plan order. {@code runId} is null and the
 * list empty while no analysis has completed. Headings the review did not plan (a title, the
 * signature block) carry no mark: Rivell did not analyse them.
 */
public record ClauseMarksView(UUID runId, List<Clause> clauses) {

    public record Clause(UUID headingNodeId, ClauseMark mark) {
    }
}
