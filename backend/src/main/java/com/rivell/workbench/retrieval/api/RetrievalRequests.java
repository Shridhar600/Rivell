package com.rivell.workbench.retrieval.api;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/** Request shapes for the M6 evidence developer surface. */
public final class RetrievalRequests {

    private RetrievalRequests() {
    }

    /**
     * The reviewed clause identifies everything the planner needs; the concept,
     * clause text and review profile are resolved from the contract's own M5
     * semantics — the frontend never invents retrieval semantics.
     */
    public record EvidenceBuildRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            UUID contractId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            int versionNumber,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            UUID clauseId) {
    }
}
