package com.rivell.workbench.knowledge;

/**
 * Type of a duplicate/conflict signal raised before publication (Architecture Decision
 * #7.15). M2 ships a deterministic hook; semantic analysis arrives with M4. Signals are
 * advisory — they surface for review and never block.
 */
public enum ConflictType {
    DUPLICATE,
    CONTRADICTS_EXISTING,
    SUPERSEDES_EXISTING,
    OVERLAPPING_EFFECTIVE_PERIOD,
    JURISDICTION_MISMATCH,
    AUTHORITY_CONFLICT,
    UNKNOWN
}
