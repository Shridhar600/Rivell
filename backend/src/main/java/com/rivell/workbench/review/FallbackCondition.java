package com.rivell.workbench.review;

/**
 * One condition of a playbook fallback as the P3 check found it, carried to the finding as a typed
 * quality value. {@code condition} is the model's short phrase; {@code status} is MET, UNMET,
 * NOT_STATED or UNKNOWN; {@code quote} is the clause or contract-fact words the status rests on,
 * or null when there is none.
 */
public record FallbackCondition(int fallback, String condition, String status, String quote) {
}
