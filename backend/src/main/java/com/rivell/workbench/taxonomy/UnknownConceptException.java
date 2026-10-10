package com.rivell.workbench.taxonomy;

/** A well-formed key that is not in the loaded taxonomy. */
public class UnknownConceptException extends IllegalArgumentException {

    public UnknownConceptException(ConceptKey key) {
        super("Unknown concept " + key);
    }
}
