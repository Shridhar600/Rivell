package com.rivell.workbench.taxonomy;

import java.util.List;

/** Reads the seeded taxonomy. Implemented in platform persistence. */
public interface ConceptRepository {

    /** Every concept, parents and leaves, in display order. */
    List<Concept> findAll();

    /** The taxonomy version the rows were seeded from. */
    int version();
}
