package com.rivell.workbench.review;

/**
 * Human-meaningful issue family: the consolidation dimension that decides whether
 * machine signals belong to one Finding (R3). Assigned by the producing check, never
 * by a model. Position families follow the policy-comparison category; the uncertain
 * family covers provider failure on a comparison, and the open-ended family the
 * machine-drafted fallback when no approved wording exists. {@code USER_ADDED} is a finding a
 * person added on a clause Rivell did not flag; no check ever produces it.
 */
public enum IssueFamily {
    MISSING_REQUIRED_PROVISION,
    EXPECTED_PROVISION_ABSENT,
    SURVIVAL_COVERAGE_GAP,
    CARVE_OUT_INCONSISTENCY,
    POLICY_UNAVAILABLE,
    POLICY_AMBIGUOUS,
    POSITION_WALK_AWAY,
    POSITION_NEEDS_FALLBACK,
    POSITION_ACCEPTABLE_FALLBACK,
    POSITION_AMBIGUOUS,
    POSITION_UNCERTAIN,
    OPEN_ENDED_POSITION,
    USER_ADDED
}
