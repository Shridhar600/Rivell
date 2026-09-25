package com.rivell.workbench.review;

/**
 * Human-meaningful issue family: the consolidation dimension that decides whether
 * machine signals belong to one Finding (R3). Assigned by the producing check, never
 * by a model.
 */
public enum IssueFamily {
    LIABILITY_CAP_DEVIATION,
    MISSING_REQUIRED_PROVISION,
    EXPECTED_PROVISION_ABSENT,
    SURVIVAL_COVERAGE_GAP,
    CARVE_OUT_INCONSISTENCY,
    POLICY_UNAVAILABLE,
    POLICY_AMBIGUOUS,
    OPEN_ENDED_LIABILITY
}
