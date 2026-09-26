package com.rivell.workbench.redline.api;

import java.util.UUID;

/** Per-approved-change execution and verification state; no OOXML internals. */
public record RedlineExecutionChangeView(
        UUID approvedChangeId,
        int ordinal,
        String actionType,
        String targetKind,
        String status,
        String verification,
        String failureCode,
        String placement,
        String resolvedTarget,
        Integer paragraphsDeleted,
        Integer paragraphsInserted) {
}
