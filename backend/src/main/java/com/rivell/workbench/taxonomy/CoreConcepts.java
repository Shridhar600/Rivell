package com.rivell.workbench.taxonomy;

import java.util.List;

/**
 * The few topics that code-owned logic names directly (fact extractors, cross-clause
 * checks, insertion placement). Everything else reaches topics as data. Each key is
 * checked against the taxonomy at startup, so a renamed topic fails fast.
 */
public final class CoreConcepts {

    public static final ConceptKey CONFIDENTIALITY_OBLIGATIONS = ConceptKey.of("CONFIDENTIALITY_OBLIGATIONS");
    public static final ConceptKey TERM = ConceptKey.of("TERM");
    public static final ConceptKey LIMITATION_OF_LIABILITY = ConceptKey.of("LIMITATION_OF_LIABILITY");
    public static final ConceptKey INDEMNIFICATION = ConceptKey.of("INDEMNIFICATION");
    public static final ConceptKey GOVERNING_LAW = ConceptKey.of("GOVERNING_LAW");

    static final List<ConceptKey> ALL = List.of(
            CONFIDENTIALITY_OBLIGATIONS, TERM, LIMITATION_OF_LIABILITY, INDEMNIFICATION, GOVERNING_LAW);

    private CoreConcepts() {
    }
}
