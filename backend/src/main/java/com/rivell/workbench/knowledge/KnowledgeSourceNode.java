package com.rivell.workbench.knowledge;

import java.util.Objects;
import java.util.UUID;

/**
 * One node of the deterministic structural parse of a knowledge source. Every node is
 * traceable to its artifact; spans mirror the contracts-module SourceSpan shape so DOCX
 * structure is represented identically across modules.
 */
public record KnowledgeSourceNode(
        UUID id,
        UUID artifactId,
        UUID parentId,
        KnowledgeNodeType nodeType,
        int ordinal,
        String text,
        Integer headingLevel,
        String numberingLabel,
        String partPath,
        int bodyIndex,
        Integer paragraphIndex,
        Integer tableIndex,
        Integer rowIndex,
        Integer cellIndex,
        Integer runStart,
        Integer runEnd,
        String sheetName,
        Integer columnIndex,
        MergedCellRegion mergedRegion,
        String rawStyleId) {

    public KnowledgeSourceNode {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(artifactId, "artifactId");
        Objects.requireNonNull(nodeType, "nodeType");
        if (ordinal < 0) {
            throw new IllegalArgumentException("Ordinal must not be negative");
        }
        if (partPath == null || partPath.isBlank()) {
            throw new IllegalArgumentException("Part path must not be blank");
        }
        if (bodyIndex < 0) {
            throw new IllegalArgumentException("Body index must not be negative");
        }
        if (rowIndex != null && rowIndex < 0) {
            throw new IllegalArgumentException("Row index must not be negative");
        }
        if (columnIndex != null && columnIndex < 0) {
            throw new IllegalArgumentException("Column index must not be negative");
        }
    }
}
