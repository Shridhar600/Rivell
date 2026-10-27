package com.rivell.workbench.review;

/**
 * Policy facts require human escalation for this deviation. A name or a trigger text alone
 * never makes an approval: {@code approver} is null unless a typed rule covers
 * the finding's stance, and {@code condition} carries the rule's own condition for the panel's
 * condition line. {@code conflictingApprover} is a lower-level rule that names a different
 * approver for the same stance, so the person can pick either.
 */
public record EscalationPayload(
        String trigger,
        Boolean requiresApproval,
        String approver,
        String condition,
        String source,
        String conflictingApprover,
        String conflictingSource,
        String stance) implements SignalPayload {

    public EscalationPayload {
        trigger = blankToNull(trigger);
        condition = blankToNull(condition);
        source = blankToNull(source);
        conflictingApprover = blankToNull(conflictingApprover);
        conflictingSource = blankToNull(conflictingSource);
        stance = blankToNull(stance);
    }

    /** A payload with no rule behind it: kept for readers of older signals. */
    public EscalationPayload(String trigger, Boolean requiresApproval) {
        this(trigger, requiresApproval, null, null, null, null, null, null);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    @Override
    @com.fasterxml.jackson.annotation.JsonProperty("kind")
    public String kind() {
        return "ESCALATION";
    }
}
