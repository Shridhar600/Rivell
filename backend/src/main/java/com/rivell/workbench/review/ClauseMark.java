package com.rivell.workbench.review;

/**
 * What one review did with one planned clause, so the user sees the whole contract marked
 * (design 2026-10-12 §9): a finding, a clean check against a playbook position, a clause no
 * position covers (only the uncovered-clause triage read it), or a check that did not finish.
 */
public enum ClauseMark {
    FLAGGED,
    CHECKED,
    NOT_COVERED,
    NOT_ANALYSED
}
