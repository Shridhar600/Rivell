package com.rivell.workbench.workflow;

/** Code-owned reasons a review cannot be finalized for redline. */
public enum FinalizationBlockerCode {
    ANALYSIS_NOT_COMPLETE,
    ANALYSIS_DEGRADED,
    /** The analysis completed, but some planned checks failed or were left unrun. */
    ANALYSIS_PARTIAL,
    UNRESOLVED_BLOCKING_FINDINGS,
    UNRESOLVED_REVIEW_REQUIRED_FINDINGS,
    ACTIVE_ESCALATION,
    APPROVED_CHANGE_CONFLICT,
    MISSING_APPROVED_LANGUAGE,
    INVALID_SOURCE_TARGET
}
