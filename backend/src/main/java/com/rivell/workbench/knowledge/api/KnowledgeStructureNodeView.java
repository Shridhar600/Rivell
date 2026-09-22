package com.rivell.workbench.knowledge.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.rivell.workbench.knowledge.KnowledgeNodeType;

public record KnowledgeStructureNodeView(
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
        Integer columnIndex,
        Integer runStart,
        Integer runEnd,
        String sheetName,
        MergedRegionView mergedRegion,
        String rawStyleId) {

    public record MergedRegionView(
            int firstRow,
            int lastRow,
            int firstColumn,
            int lastColumn) {
    }
}
