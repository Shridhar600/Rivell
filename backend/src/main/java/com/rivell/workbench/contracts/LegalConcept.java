package com.rivell.workbench.contracts;

/**
 * Bounded initial legal-concept vocabulary for semantic classification. Concepts are
 * deliberately non-exclusive: a source region may express several. Lives with the
 * canonical contract model as the shared bridge between contract clauses and
 * organizational knowledge (AD #5.21); the knowledge module consumes it.
 */
public enum LegalConcept {
    CONFIDENTIALITY,
    TERM_TERMINATION,
    LIMITATION_OF_LIABILITY,
    INDEMNIFICATION,
    GOVERNING_LAW
}
