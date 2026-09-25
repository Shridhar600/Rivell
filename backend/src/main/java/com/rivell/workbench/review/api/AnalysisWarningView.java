package com.rivell.workbench.review.api;

/**
 * One check whose degradation/failure means the analysis could not fully trust its
 * inputs. The reason is a bounded, code-owned reason code plus a safe message; raw
 * exception classes, source locations, prompts, and contract text never appear here.
 */
public record AnalysisWarningView(
        String checkKey, String status, String reasonCode, String message) {
}
