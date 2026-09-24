package com.rivell.workbench.retrieval;

/**
 * Authority lane of an evidence item (Architecture Decision #8). Authority is a
 * property of the source, fixed by construction — never changed by similarity,
 * semantic assessment, or fusion. The conceptual order is authority, then
 * applicability, then relevance.
 */
public enum AuthorityClass {
    POLICY,
    APPROVED_DRAFTING,
    PRECEDENT,
    CURRENT_CONTRACT,
    EXTERNAL_REFERENCE
}
