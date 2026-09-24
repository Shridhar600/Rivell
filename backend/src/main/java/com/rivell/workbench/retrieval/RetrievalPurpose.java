package com.rivell.workbench.retrieval;

/**
 * Versioned retrieval intent (Architecture Decision #8). A purpose tells the planner
 * which evidence lanes matter; it is never free text. M6 implements only the purpose
 * its fixtures and architecture justify; later purposes are added when consumers
 * (M7 review) require them.
 */
public enum RetrievalPurpose {

    /**
     * Authority evidence for reviewing one clause region of a contract version:
     * applicable policy, approved drafting, comparable precedent, and the
     * current-contract semantic context of that clause.
     */
    CLAUSE_REVIEW
}
