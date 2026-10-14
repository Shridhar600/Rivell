package com.rivell.workbench.review;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * One mark per planned clause of one run. A finding wins, because the user must see a flag;
 * then a check that did not complete, because Rivell did not finish looking; then whether a
 * playbook position addressed the clause. Pure: the frozen plan, one run's executions and the
 * same run's findings.
 */
public final class ClauseMarks {

    private ClauseMarks() {
    }

    public static LinkedHashMap<UUID, ClauseMark> of(
            List<ReviewPlanCheck> checks, List<ReviewCheckExecution> executions,
            List<Finding> findings) {
        Set<UUID> flagged = findings.stream()
                .filter(finding -> finding.subjectKind() == FindingSubjectKind.CLAUSE)
                .map(Finding::subjectHeadingNodeId)
                .collect(Collectors.toSet());
        Map<UUID, ReviewCheckExecution> byCheck = executions.stream()
                .collect(Collectors.toMap(ReviewCheckExecution::planCheckId,
                        execution -> execution, (first, second) -> first));
        var clauseChecks = new LinkedHashMap<UUID, List<ReviewPlanCheck>>();
        for (var check : checks) {
            if (check.subjectHeadingNodeId() != null) {
                clauseChecks.computeIfAbsent(check.subjectHeadingNodeId(),
                        heading -> new ArrayList<>()).add(check);
            }
        }
        var marks = new LinkedHashMap<UUID, ClauseMark>();
        clauseChecks.forEach((heading, planned) ->
                marks.put(heading, mark(heading, planned, byCheck, flagged)));
        return marks;
    }

    private static ClauseMark mark(
            UUID heading, List<ReviewPlanCheck> planned,
            Map<UUID, ReviewCheckExecution> byCheck, Set<UUID> flagged) {
        if (flagged.contains(heading)) {
            return ClauseMark.FLAGGED;
        }
        for (var check : planned) {
            var execution = byCheck.get(check.id());
            if (execution == null || execution.status() != CheckExecutionStatus.COMPLETED) {
                return ClauseMark.NOT_ANALYSED;
            }
        }
        for (var check : planned) {
            if (checked(check, byCheck.get(check.id()))) {
                return ClauseMark.CHECKED;
            }
        }
        return ClauseMark.NOT_COVERED;
    }

    private static boolean checked(ReviewPlanCheck check, ReviewCheckExecution execution) {
        if (check.mechanism() != CheckMechanism.POLICY_COMPARISON) {
            return true;
        }
        if (execution.detail().get("addressed") instanceof Boolean addressed) {
            return addressed;
        }
        // runs from before the comparison recorded "addressed": any compared position counts
        return execution.detail().get("positionCount") instanceof Number count
                && count.intValue() > 0;
    }
}
