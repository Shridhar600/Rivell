package com.rivell.workbench.workflow;

import java.util.UUID;

import com.rivell.workbench.redline.ChangeActionType;

/** One decision write request after HTTP decoding; expectedVersion is required. */
public record DecisionCommand(
        Integer expectedVersion,
        UUID clientRequestId,
        DecisionAction action,
        ChangeActionType approvedAction,
        String approvedLanguage,
        String comment) {
}
