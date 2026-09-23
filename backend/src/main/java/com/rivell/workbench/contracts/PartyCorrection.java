package com.rivell.workbench.contracts;

import java.util.List;
import java.util.UUID;

/**
 * Human correction layer for party roles. NONE means no correction exists; a
 * correction preserves the machine proposal untouched and records who/when via a
 * correction event id.
 */
public record PartyCorrection(List<PartyRole> roles, UUID correctionId) {

    public static final PartyCorrection NONE = new PartyCorrection(List.of(), null);

    public PartyCorrection {
        roles = roles == null ? List.of() : List.copyOf(roles);
    }

    public static PartyCorrection of(List<PartyRole> roles) {
        return new PartyCorrection(roles, UUID.randomUUID());
    }

    public boolean corrected() {
        return correctionId != null;
    }
}
