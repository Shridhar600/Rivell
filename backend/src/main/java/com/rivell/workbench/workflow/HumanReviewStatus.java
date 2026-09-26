package com.rivell.workbench.workflow;

/** Derived human-review lifecycle of a review; never persisted. */
public enum HumanReviewStatus {
    NOT_STARTED,
    IN_PROGRESS,
    READY_FOR_COMPLETION,
    COMPLETED
}
