package com.rivell.workbench.review;

/** Machine-analysis lifecycle of a Review. Human authority begins in M8. */
public enum ReviewStatus {
    ANALYSIS_QUEUED,
    ANALYZING,
    COMPLETED,
    FAILED
}
