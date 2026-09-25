package com.rivell.workbench.review;

import java.util.Objects;

/** Typed cross-clause conflict observation (bounded relationship check). */
public record CrossClauseConflictPayload(String conflictType, String detail)
        implements SignalPayload {

    public CrossClauseConflictPayload {
        Objects.requireNonNull(conflictType, "conflictType");
        Objects.requireNonNull(detail, "detail");
    }

    @Override
    @com.fasterxml.jackson.annotation.JsonProperty("kind")
    public String kind() {
        return "CROSS_CLAUSE";
    }
}
