package com.rivell.workbench.review.api;

/**
 * One check whose degradation/failure means the analysis could not fully trust its
 * inputs. The reason is a bounded, sanitized code-generated string; no prompts,
 * contract text, or provider internals are exposed.
 */
public record AnalysisWarningView(String checkKey, String status, String reason) {
}
