package com.rivell.workbench.knowledge;

/**
 * Known jurisdictions in the current bounded scope vocabulary. Scope equality and
 * overlap checks are code operations; the model may only classify which known
 * jurisdiction a source names.
 */
public enum KnownJurisdiction {
    US("United States"),
    UK("United Kingdom"),
    UNSPECIFIED("No specific jurisdiction named in the unit.");

    private final String description;

    KnownJurisdiction(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
