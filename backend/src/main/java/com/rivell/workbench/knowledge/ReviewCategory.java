package com.rivell.workbench.knowledge;

/**
 * Deterministic review-risk category composed from explicit signals. Answers how much
 * human scrutiny a candidate requires before publication — not legal severity.
 */
public enum ReviewCategory {
    ROUTINE,
    ELEVATED,
    HIGH
}
