package com.rivell.workbench.workflow;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** One typed finalization blocker with a safe message and bounded finding references. */
public record FinalizationBlocker(
        FinalizationBlockerCode code, String message, List<UUID> findingIds) {

    public FinalizationBlocker {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        findingIds = findingIds == null ? List.of() : List.copyOf(findingIds);
    }
}
