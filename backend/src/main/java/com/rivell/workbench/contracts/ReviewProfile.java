package com.rivell.workbench.contracts;

import java.util.Objects;

/**
 * User-declared enrichment context. Disambiguates interpretation; never a policy
 * engine. Every value is human-declared and recorded verbatim.
 */
public record ReviewProfile(
        ContractType contractType,
        PartyRole ourSideRole,
        PaperSource paperSource,
        Jurisdiction jurisdiction) {

    public ReviewProfile {
        Objects.requireNonNull(contractType, "contractType");
    }

    public static ReviewProfile minimal(ContractType contractType) {
        return new ReviewProfile(contractType, null, null, Jurisdiction.UNSPECIFIED);
    }
}
