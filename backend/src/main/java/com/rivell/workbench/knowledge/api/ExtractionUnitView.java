package com.rivell.workbench.knowledge.api;

import java.util.List;
import java.util.UUID;

import com.rivell.workbench.knowledge.ExtractionUnitKind;
import com.rivell.workbench.knowledge.KnowledgeRecordKind;

public record ExtractionUnitView(
        UUID id,
        UUID artifactId,
        int ordinal,
        ExtractionUnitKind kind,
        String title,
        String contentText,
        List<UUID> sourceNodeIds,
        KnowledgeRecordKind recordKind) {
}
