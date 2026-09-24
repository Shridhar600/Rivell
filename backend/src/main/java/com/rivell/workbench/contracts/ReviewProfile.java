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
        Jurisdiction jurisdiction,
        String contractPackKey) {

    public ReviewProfile {
        Objects.requireNonNull(contractType, "contractType");
    }

    /** Explicitly selected Contract Pack; null = unspecified (never inferred). */
    public ReviewProfile(
            ContractType contractType, PartyRole ourSideRole, PaperSource paperSource,
            Jurisdiction jurisdiction) {
        this(contractType, ourSideRole, paperSource, jurisdiction, null);
    }

    public static ReviewProfile minimal(ContractType contractType) {
        return new ReviewProfile(contractType, null, null, Jurisdiction.UNSPECIFIED, null);
    }
}
