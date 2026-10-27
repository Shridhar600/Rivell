package com.rivell.workbench.review.check;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.rivell.workbench.review.FindingAssembler;
import com.rivell.workbench.review.FindingSubjectKind;
import com.rivell.workbench.review.IssueFamily;
import com.rivell.workbench.review.OmissionPayload;
import com.rivell.workbench.review.RawReviewSignal;
import com.rivell.workbench.review.ReviewAnalysisRun;
import com.rivell.workbench.review.RunStatus;
import com.rivell.workbench.review.RunTrigger;
import com.rivell.workbench.review.SignalType;
import com.rivell.workbench.review.WalkAwayAbsenceSpecs;
import com.rivell.workbench.taxonomy.CoreConcepts;

class OmissionCheckExecutorTest {

    private static final UUID ORGANIZATION_ID = UUID.randomUUID();
    private static final UUID REVIEW_ID = UUID.randomUUID();
    private static final UUID RUN_ID = UUID.randomUUID();
    private static final UUID HEADING_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");

    @Test
    void namesTheMatchedClauseByItsBoundedDisplayHeading() {
        // a run-in heading: the node text is the whole paragraph, the heading part names the clause
        var paragraph = "Termination. Either party may terminate this agreement for convenience "
                + "on thirty days' notice.";
        assertThat(OmissionCheckExecutor.matchedClauseName(
                Map.of(HEADING_ID, paragraph), Map.of(HEADING_ID, "Termination."), HEADING_ID))
                .isEqualTo("Termination.");

        var longHeading = "Confidentiality obligations that survive termination of this agreement "
                + "for a period of five years after it ends";
        assertThat(OmissionCheckExecutor.matchedClauseName(
                Map.of(HEADING_ID, longHeading), Map.of(), HEADING_ID))
                .isEqualTo(
                        "Confidentiality obligations that survive termination of this agreement "
                                + "for a…");

        // a matched clause the structure could not name still says a clause matched
        assertThat(OmissionCheckExecutor.matchedClauseName(Map.of(), Map.of(), HEADING_ID))
                .isEmpty();
        var finding = new FindingAssembler()
                .assemble(run(), List.of(walkAwaySignal()), NOW).getFirst();
        assertThat(finding.observationText()).isEqualTo(
                "Your playbook's walk-away: No termination for convenience. "
                        + "The closest clause matches it.");
    }

    private static ReviewAnalysisRun run() {
        return new ReviewAnalysisRun(RUN_ID, ORGANIZATION_ID, REVIEW_ID, 1,
                RunStatus.COMPLETED, RunTrigger.INITIAL, "review-pipeline-1",
                NOW, NOW, null, 0, null, null);
    }

    private static RawReviewSignal walkAwaySignal() {
        var candidateId = UUID.randomUUID();
        var omission = new OmissionPayload(candidateId, "Termination for convenience", 12,
                "No termination for convenience", "");
        return new RawReviewSignal(UUID.randomUUID(), ORGANIZATION_ID, REVIEW_ID, RUN_ID,
                UUID.randomUUID(), SignalType.MISSING_REQUIRED_CONCEPT,
                FindingSubjectKind.ABSENCE, null, CoreConcepts.TERM, CoreConcepts.TERM, null,
                IssueFamily.MISSING_REQUIRED_PROVISION, omission, List.of(UUID.randomUUID()),
                WalkAwayAbsenceSpecs.WALK_AWAY_ABSENCE_SPEC,
                WalkAwayAbsenceSpecs.WALK_AWAY_ABSENCE_VERSION, null, null, null, null, null,
                Map.of("absenceProbability", 0.92, "route", "JEV"),
                SignalType.MISSING_REQUIRED_CONCEPT.name() + ":" + candidateId, NOW);
    }
}
