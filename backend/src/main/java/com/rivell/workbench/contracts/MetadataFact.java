package com.rivell.workbench.contracts;

import java.util.Objects;
import java.util.UUID;

/**
 * One first-class contract metadata fact with provenance and correction state.
 * The field set is bounded; values are string-encoded and interpreted per field.
 */
public record MetadataFact(
        UUID id,
        UUID contractVersionId,
        MetadataField field,
        String value,
        Interpretation interpretation,
        String correctedValue,
        UUID correctedBy) {

    public MetadataFact {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(contractVersionId, "contractVersionId");
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(interpretation, "interpretation");
    }

    public String effectiveValue() {
        return correctedValue != null && !correctedValue.isBlank() ? correctedValue : value;
    }

    public boolean corrected() {
        return correctedValue != null && !correctedValue.isBlank();
    }
}
