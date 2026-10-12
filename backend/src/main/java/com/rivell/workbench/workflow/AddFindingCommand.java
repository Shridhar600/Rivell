package com.rivell.workbench.workflow;

import java.util.UUID;

import com.rivell.workbench.review.FindingSeverity;

/** A person's finding on one planned clause: what Rivell missed, and how serious it is. */
public record AddFindingCommand(UUID headingNodeId, FindingSeverity severity, String note) {
}
