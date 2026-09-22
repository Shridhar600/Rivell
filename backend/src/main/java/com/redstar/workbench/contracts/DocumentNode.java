package com.redstar.workbench.contracts;

import java.util.Objects;
import java.util.UUID;

public record DocumentNode(
        UUID id,
        UUID contractVersionId,
        UUID parentId,
        DocumentNodeType nodeType,
        int ordinal,
        String text,
        Integer headingLevel,
        SourceSpan sourceSpan,
        String rawStyleId) {

    public DocumentNode {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(contractVersionId, "contractVersionId");
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
