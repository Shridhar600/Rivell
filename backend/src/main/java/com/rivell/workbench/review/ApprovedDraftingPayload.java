package com.rivell.workbench.review;

import java.util.Objects;
import java.util.UUID;

/** Published approved drafting that fits the issue; exact source wording preserved. */
public record ApprovedDraftingPayload(UUID draftingEvidenceId, String evidenceText)
        implements SignalPayload {

    public ApprovedDraftingPayload {
        Objects.requireNonNull(draftingEvidenceId, "draftingEvidenceId");
        Objects.requireNonNull(evidenceText, "evidenceText");
    }

    @Override
    @com.fasterxml.jackson.annotation.JsonProperty("kind")
    public String kind() {
        return "APPROVED_DRAFTING";
    }
}
