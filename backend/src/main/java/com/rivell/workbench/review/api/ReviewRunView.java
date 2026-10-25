package com.rivell.workbench.review.api;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/** Identity of one analysis run (generation), with its frozen playbook coverage. */
public record ReviewRunView(
        UUID id,
        int runNumber,
        String status,
        @Schema(nullable = true,
                description = "What the run did with each applicable published playbook position; "
                        + "null on runs that predate the coverage list or whose coverage could "
                        + "not be computed")
        List<PlaybookCoverageEntryView> playbookCoverage) {
}
