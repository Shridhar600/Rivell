package com.rivell.workbench.retrieval;

/**
 * Versioned retrieval intent (Architecture Decision #8). A purpose tells the planner
 * which evidence lanes matter; it is never free text. Only purposes with a real
 * consumer exist; others are added when a consumer requires them.
 */
public enum RetrievalPurpose {

    /**
     * Authority evidence for reviewing one clause region of a contract version:
     * applicable policy, approved drafting, comparable precedent, and the
     * current-contract semantic context of that clause.
     */
    CLAUSE_REVIEW
}
