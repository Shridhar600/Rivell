package com.rivell.workbench.knowledge.api;

import jakarta.validation.constraints.NotNull;

/**
 * Human concept correction on a knowledge candidate.
 * `primary` is required: a taxonomy key to set, or "" to clear the
 * concept to unclassified. Machine history is preserved.
 */
public record ConceptCorrectionRequest(@NotNull String primary) {
}
