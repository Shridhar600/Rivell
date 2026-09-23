package com.rivell.workbench.contracts.api;

import com.rivell.workbench.contracts.ContractType;
import com.rivell.workbench.contracts.Jurisdiction;
import com.rivell.workbench.contracts.PaperSource;
import com.rivell.workbench.contracts.PartyRole;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Human-declared enrichment context (M5). Supplies disambiguation context only —
 * never review policy.
 */
public record ReviewProfileInput(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Declared contract type for enrichment")
        ContractType contractType,
        PartyRole ourSideRole,
        PaperSource paperSource,
        Jurisdiction jurisdiction) {

    public ReviewProfileInput {
        if (contractType == null) {
            throw new IllegalArgumentException("contractType is required");
        }
    }

    public com.rivell.workbench.contracts.ReviewProfile toProfile() {
        return new com.rivell.workbench.contracts.ReviewProfile(
                contractType, ourSideRole, paperSource,
                jurisdiction == null ? Jurisdiction.UNSPECIFIED : jurisdiction);
    }
}
