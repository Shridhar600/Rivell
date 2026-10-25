package com.rivell.workbench.knowledge;

/**
 * Known jurisdictions in the current bounded scope vocabulary. Scope equality and
 * overlap checks are code operations; the model may only classify which known
 * jurisdiction a source names.
 */
public enum KnownJurisdiction {
    US("The unit names the United States or a US state."),
    UK("The unit names the United Kingdom, England and Wales, Scotland or Northern Ireland."),
    AUSTRALIA("The unit names Australia or an Australian state or territory."),
    CANADA("The unit names Canada or a Canadian province or territory."),
    IRELAND("The unit names Ireland."),
    INDIA("The unit names India."),
    NEW_ZEALAND("The unit names New Zealand."),
    SINGAPORE("The unit names Singapore."),
    HONG_KONG("The unit names Hong Kong."),
    GERMANY("The unit names Germany."),
    FRANCE("The unit names France."),
    NETHERLANDS("The unit names the Netherlands."),
    OTHER("The unit names a jurisdiction that is not in this list."),
    UNSPECIFIED("No specific jurisdiction named in the unit.");

    private final String description;

    KnownJurisdiction(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
