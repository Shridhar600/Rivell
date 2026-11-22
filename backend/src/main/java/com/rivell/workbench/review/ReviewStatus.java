package com.rivell.workbench.review;

/** Machine-analysis lifecycle of a Review. Human authority is recorded separately. */
public enum ReviewStatus {
    ANALYSIS_QUEUED,
    ANALYZING,
    COMPLETED,
    FAILED
}
