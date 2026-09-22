package com.rivell.workbench.knowledge;

/**
 * Review state of a knowledge candidate. Only an explicit human-controlled publication
 * step converts a candidate into organizational truth (Architecture Decision #6.11):
 * a published candidate is immutable and can never return to review.
 */
public enum ReviewStatus {
    PENDING,
    APPROVED,
    EDITED,
    REJECTED,
    PUBLISHED
}
