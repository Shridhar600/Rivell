package com.rivell.workbench.knowledge.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.rivell.workbench.knowledge.ExtractionUnit;
import com.rivell.workbench.knowledge.ExtractionUnitKind;
import com.rivell.workbench.knowledge.KnowledgeRecordKind;

/**
 * One part of a knowledge file. {@code recordKind} is Rivell's reading of the part; the three
 * {@code recordKindOverride…} fields are a person's correction of it ("make this a position"),
 * all null when nobody corrected the part.
 */
public record ExtractionUnitView(
        UUID id,
        UUID artifactId,
        int ordinal,
        ExtractionUnitKind kind,
        String title,
        String contentText,
        List<UUID> sourceNodeIds,
        KnowledgeRecordKind recordKind,
        KnowledgeRecordKind recordKindOverride,
        UUID recordKindOverriddenBy,
        Instant recordKindOverriddenAt) {

    static ExtractionUnitView from(ExtractionUnit unit) {
        var override = unit.override();
        return new ExtractionUnitView(unit.id(), unit.artifactId(), unit.ordinal(), unit.kind(),
                unit.title(), unit.contentText(), unit.sourceNodeIds(), unit.recordKind(),
                override == null ? null : override.kind(),
                override == null ? null : override.by(),
                override == null ? null : override.at());
    }
}
