package com.rivell.workbench.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.rivell.workbench.taxonomy.ConceptKey;

class ClauseMarksTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID REVIEW = UUID.randomUUID();
    private static final UUID RUN = UUID.randomUUID();
    private static final ConceptKey TOPIC = ConceptKey.of("LIMITATION_OF_LIABILITY");

    private final List<ReviewPlanCheck> checks = new ArrayList<>();
    private final List<ReviewCheckExecution> executions = new ArrayList<>();
    private final List<Finding> findings = new ArrayList<>();

    private ReviewPlanCheck plan(UUID heading, CheckMechanism mechanism) {
        var check = new ReviewPlanCheck(UUID.randomUUID(), ORG, REVIEW, checks.size(),
                "check-" + checks.size(), CheckFamily.CLAUSE_REVIEW, null, mechanism, TOPIC, null,
                heading, Instant.now());
        checks.add(check);
        return check;
    }

    private void ran(ReviewPlanCheck check, CheckExecutionStatus status, Map<String, Object> detail) {
        executions.add(new ReviewCheckExecution(UUID.randomUUID(), ORG, REVIEW, RUN, check.id(),
                status, null, detail, 0, Instant.now(), Instant.now()));
    }

    private void flag(UUID heading) {
        findings.add(new Finding(UUID.randomUUID(), ORG, REVIEW, RUN, "k-" + findings.size(),
                FindingSubjectKind.CLAUSE, heading, null, TOPIC, null,
                IssueFamily.POSITION_NEEDS_FALLBACK, "POSITION_DEVIATION", "text",
                FindingSeverity.MEDIUM, FindingClassification.REVIEW_REQUIRED,
                ProposedAction.ESCALATE_TO_HUMAN, null, ProposedLanguageSource.NONE, null, Map.of(),
                Instant.now(), List.of(), List.of(), List.of(), List.of(), null));
    }

    private ClauseMark markOf(UUID heading) {
        return ClauseMarks.of(checks, executions, findings).get(heading);
    }

    @Test
    void aClauseWithAFindingIsFlaggedEvenIfItsCheckDegraded() {
        var heading = UUID.randomUUID();
        ran(plan(heading, CheckMechanism.POLICY_COMPARISON), CheckExecutionStatus.DEGRADED, Map.of());
        flag(heading);

        assertThat(markOf(heading)).isEqualTo(ClauseMark.FLAGGED);
    }

    @Test
    void aClauseWhoseCheckDidNotCompleteIsNotAnalysedUnlessFlagged() {
        var degraded = UUID.randomUUID();
        var failed = UUID.randomUUID();
        var skipped = UUID.randomUUID();
        var neverRan = UUID.randomUUID();
        var halfDone = UUID.randomUUID();
        ran(plan(degraded, CheckMechanism.POLICY_COMPARISON), CheckExecutionStatus.DEGRADED,
                Map.of("addressed", true));
        ran(plan(failed, CheckMechanism.POLICY_COMPARISON), CheckExecutionStatus.FAILED, Map.of());
        ran(plan(skipped, CheckMechanism.POLICY_COMPARISON), CheckExecutionStatus.SKIPPED, Map.of());
        plan(neverRan, CheckMechanism.POLICY_COMPARISON);
        // one of two checks on a clause completed: Rivell still did not finish looking at it
        ran(plan(halfDone, CheckMechanism.POLICY_COMPARISON), CheckExecutionStatus.COMPLETED,
                Map.of("addressed", true));
        ran(plan(halfDone, CheckMechanism.CONFIDENTIALITY_SURVIVAL), CheckExecutionStatus.FAILED,
                Map.of());

        var marks = ClauseMarks.of(checks, executions, findings);

        assertThat(List.of(degraded, failed, skipped, neverRan, halfDone))
                .allSatisfy(heading -> assertThat(marks.get(heading))
                        .isEqualTo(ClauseMark.NOT_ANALYSED));
    }

    @Test
    void aCompletedComparisonWithAnAddressingPositionIsChecked() {
        var heading = UUID.randomUUID();
        ran(plan(heading, CheckMechanism.POLICY_COMPARISON), CheckExecutionStatus.COMPLETED,
                Map.of("addressed", true, "positionCount", 2));

        assertThat(markOf(heading)).isEqualTo(ClauseMark.CHECKED);
    }

    @Test
    void aCompletedComparisonNoPositionAddressesIsNotCovered() {
        var heading = UUID.randomUUID();
        ran(plan(heading, CheckMechanism.POLICY_COMPARISON), CheckExecutionStatus.COMPLETED,
                Map.of("addressed", false, "positionCount", 2));

        assertThat(markOf(heading)).isEqualTo(ClauseMark.NOT_COVERED);
    }

    @Test
    void aRunFromBeforeTheAddressedFlagFallsBackToThePositionCount() {
        var compared = UUID.randomUUID();
        var triageOnly = UUID.randomUUID();
        ran(plan(compared, CheckMechanism.POLICY_COMPARISON), CheckExecutionStatus.COMPLETED,
                Map.of("positionCount", 2));
        ran(plan(triageOnly, CheckMechanism.POLICY_COMPARISON), CheckExecutionStatus.COMPLETED,
                Map.of("positionCount", 0));

        var marks = ClauseMarks.of(checks, executions, findings);

        assertThat(marks.get(compared)).isEqualTo(ClauseMark.CHECKED);
        assertThat(marks.get(triageOnly)).isEqualTo(ClauseMark.NOT_COVERED);
    }

    @Test
    void aCompletedSurvivalOrCarveOutCheckCountsAsChecked() {
        var survival = UUID.randomUUID();
        var carveOut = UUID.randomUUID();
        ran(plan(survival, CheckMechanism.CONFIDENTIALITY_SURVIVAL), CheckExecutionStatus.COMPLETED,
                Map.of());
        ran(plan(carveOut, CheckMechanism.CARVE_OUT_CONSISTENCY), CheckExecutionStatus.COMPLETED,
                Map.of());

        var marks = ClauseMarks.of(checks, executions, findings);

        assertThat(marks.get(survival)).isEqualTo(ClauseMark.CHECKED);
        assertThat(marks.get(carveOut)).isEqualTo(ClauseMark.CHECKED);
    }

    @Test
    void presenceChecksHaveNoClauseAndGetNoMarkAndClausesKeepPlanOrder() {
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        checks.add(new ReviewPlanCheck(UUID.randomUUID(), ORG, REVIEW, 0, "presence",
                CheckFamily.CONCEPT_PRESENCE, CheckExpectation.values()[0],
                CheckMechanism.PRESENCE, TOPIC, null, null, Instant.now()));
        ran(plan(first, CheckMechanism.POLICY_COMPARISON), CheckExecutionStatus.COMPLETED,
                Map.of("addressed", true));
        ran(plan(second, CheckMechanism.POLICY_COMPARISON), CheckExecutionStatus.COMPLETED,
                Map.of("addressed", false));

        assertThat(ClauseMarks.of(checks, executions, findings).keySet())
                .containsExactly(first, second);
    }
}
