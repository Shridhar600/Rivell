package com.rivell.workbench.workflow.api;

import java.util.UUID;

import com.rivell.workbench.review.FindingSeverity;

/** A finding the user adds: the planned clause, its severity (default MEDIUM) and the note. */
public record AddFindingRequest(UUID headingNodeId, FindingSeverity severity, String note) {
}
