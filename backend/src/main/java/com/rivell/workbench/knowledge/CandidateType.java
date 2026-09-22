package com.rivell.workbench.knowledge;

/**
 * Family of a typed knowledge candidate (Architecture Decision #7.12). The subtype is
 * derived deterministically from the artifact type at compile time.
 */
public enum CandidateType {
    PLAYBOOK_RULE,
    CLAUSE_VARIANT,
    TEMPLATE_CLAUSE,
    PRECEDENT,
    REFERENCE
}
