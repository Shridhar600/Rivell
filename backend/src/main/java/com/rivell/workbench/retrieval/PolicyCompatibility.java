package com.rivell.workbench.retrieval;

/**
 * Whether a precedent is compatible with the currently applicable published policy
 * (AD #8.3). Compatibility is an assessment property of precedent evidence relative
 * to policy — it never promotes precedent into policy. When policy is missing or
 * ambiguous, no compatibility question is asked and the state is NOT_ASSESSED.
 */
public enum PolicyCompatibility {
    COMPATIBLE,
    INCOMPATIBLE,
    UNKNOWN,
    NOT_ASSESSED
}
