package com.rivell.workbench.contracts;

import java.util.Objects;
import java.util.UUID;

/**
 * One node of a deterministic structural parse. {@code ownerAggregateId} identifies the
 * domain aggregate the tree belongs to (a contract version, a knowledge artifact, ...).
 */
public record DocumentNode(
        UUID id,
        UUID ownerAggregateId,
        UUID parentId,
        DocumentNodeType nodeType,
        int ordinal,
        String text,
        Integer headingLevel,
        String numberingLabel,
        SourceSpan sourceSpan,
        String rawStyleId) {

    public DocumentNode {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerAggregateId, "ownerAggregateId");
        Objects.requireNonNull(nodeType, "nodeType");
        if (ordinal < 0) {
            throw new IllegalArgumentException("Ordinal must not be negative");
        }
        Objects.requireNonNull(sourceSpan, "sourceSpan");
        if (headingLevel != null && headingLevel < 1) {
            throw new IllegalArgumentException("Heading level must be positive");
        }
    }
}
