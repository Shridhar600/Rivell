package com.redstar.workbench.contracts.api;

import java.util.List;
import java.util.UUID;

public record ContractStructureResponse(
        UUID contractId,
        int versionNumber,
        String parserVersion,
        String parseStatus,
        int nodeCount,
        List<NodeView> nodes) {

    public record NodeView(
            UUID id,
            UUID parentId,
            String nodeType,
            int ordinal,
            String text,
            Integer headingLevel,
            SourceSpanView sourceSpan,
            String rawStyleId) {
    }

    public record SourceSpanView(
            String partPath,
            int bodyIndex,
            Integer paragraphIndex,
            Integer tableIndex,
            Integer rowIndex,
            Integer cellIndex,
            Integer runStart,
            Integer runEnd) {
    }
}
