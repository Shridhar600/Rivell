package com.rivell.workbench.workflow.api;

import java.util.List;
import java.util.UUID;

/** Typed finalization blocker with a safe message and bounded finding references. */
public record BlockerView(String code, String message, List<UUID> findingIds) {
}
