package com.rivell.workbench.contracts.api;

import java.util.List;
import java.util.UUID;

import com.rivell.workbench.contracts.PartyRole;

import io.swagger.v3.oas.annotations.media.Schema;

/** Human correction requests over M5 machine interpretation. */
public final class CorrectionRequests {

    private CorrectionRequests() {
    }

    public record PartyRoleCorrection(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "Corrected roles of the party in this agreement")
            List<PartyRole> roles) {
    }

    public record ConceptCorrection(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "Corrected primary concept as a taxonomy key; any "
                            + "known key is accepted (unknown names are rejected) "
                            + "and becomes the effective primary even if previously "
                            + "unclassified. null leaves the current correction unchanged; "
                            + "blank clears the concept correction")
            String primary) {
    }

    public record FactValuesCorrection(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "Corrected fact values; fields must belong to the "
                            + "clause's existing fact schema")
            java.util.Map<String, String> values) {
    }
}
