package com.rivell.workbench.review;

import java.util.Objects;

/** Typed open-ended candidate result: machine-proposed wording + explanation + verification. */
public record OpenEndedAnalysisPayload(
        String proposedLanguage, String explanation, String verificationOutcome)
        implements SignalPayload {

    public OpenEndedAnalysisPayload {
        Objects.requireNonNull(proposedLanguage, "proposedLanguage");
        Objects.requireNonNull(explanation, "explanation");
        Objects.requireNonNull(verificationOutcome, "verificationOutcome");
    }

    @Override
    @com.fasterxml.jackson.annotation.JsonProperty("kind")
    public String kind() {
        return "OPEN_ENDED";
    }
}
