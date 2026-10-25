package com.rivell.workbench.review.api;

import java.util.UUID;

import com.rivell.workbench.review.PlaybookCoverage;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One frozen playbook coverage row: the position a reader sees, the state the run left it in,
 * and the clause or finding a reviewer can open for a judged position. {@code verdict} is
 * present only for {@code COMPARED}; {@code reason} only for {@code NOT_COMPARED}.
 */
@Schema(description = "What one analysis run did with one applicable published playbook position")
public record PlaybookCoverageEntryView(
        UUID positionCandidateId,
        String label,
        @Schema(nullable = true) String code,
        @Schema(nullable = true) String topic,
        PlaybookCoverage.State state,
        @Schema(nullable = true) PlaybookCoverage.Verdict verdict,
        @Schema(nullable = true) UUID clauseHeadingNodeId,
        @Schema(nullable = true) UUID findingId,
        @Schema(nullable = true) PlaybookCoverage.Reason reason) {

    static PlaybookCoverageEntryView from(PlaybookCoverage.Entry entry) {
        return new PlaybookCoverageEntryView(
                entry.positionCandidateId(), entry.label(), entry.code(), entry.topic(),
                entry.state(), entry.verdict(), entry.clauseHeadingNodeId(), entry.findingId(),
                entry.reason());
    }
}
