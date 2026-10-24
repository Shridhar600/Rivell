package com.rivell.workbench.review;

import java.util.Objects;
import java.util.UUID;

/**
 * Published clause-library wording offered for a position, with the exact source wording
 * preserved. {@code fitVerdict} is the fit check's verdict on this wording for the position it
 * serves: FITS, DOES_NOT_FIT or UNKNOWN. Null means the wording predates the fit check and keeps
 * its old offer.
 */
public record ApprovedDraftingPayload(
        UUID draftingEvidenceId, String evidenceText, String variantRole, String fitVerdict)
        implements SignalPayload {

    public ApprovedDraftingPayload {
        Objects.requireNonNull(draftingEvidenceId, "draftingEvidenceId");
        Objects.requireNonNull(evidenceText, "evidenceText");
        if (fitVerdict != null && !java.util.Set.of(FitVerdicts.FITS,
                FitVerdicts.DOES_NOT_FIT, FitVerdicts.UNKNOWN).contains(fitVerdict)) {
            throw new IllegalArgumentException("unknown wording fit verdict: " + fitVerdict);
        }
    }

    @Override
    @com.fasterxml.jackson.annotation.JsonProperty("kind")
    public String kind() {
        return "APPROVED_DRAFTING";
    }
}
