package com.rivell.workbench.knowledge.api;

import jakarta.validation.constraints.NotNull;

/**
 * Human LegalConcept correction on a knowledge candidate (DW-017).
 * `primary` is required: a LegalConcept name to set, or "" to clear the
 * concept to unclassified. Machine history is preserved.
 */
public record ConceptCorrectionRequest(String primary) {
}
