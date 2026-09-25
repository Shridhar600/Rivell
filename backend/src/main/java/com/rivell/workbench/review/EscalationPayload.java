package com.rivell.workbench.review;

/** Policy facts require human escalation for this deviation. */
public record EscalationPayload(String trigger, boolean requiresApproval)
        implements SignalPayload {

    @Override
    @com.fasterxml.jackson.annotation.JsonProperty("kind")
    public String kind() {
        return "ESCALATION";
    }
}
