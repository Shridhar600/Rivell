package com.rivell.workbench.contracts.api;

import java.util.List;
import java.util.UUID;

import com.rivell.workbench.contracts.DocumentNodeType;
import com.rivell.workbench.contracts.ParseStatus;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Canonical structure of a contract version")
public record ContractStructureResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID contractId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int versionNumber,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String parserVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, implementation = ParseStatus.class) ParseStatus parseStatus,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> parseWarnings,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int nodeCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<NodeView> nodes) {

    @Schema(description = "One node of the canonical document tree")
    public record NodeView(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) UUID parentId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, implementation = DocumentNodeType.class) DocumentNodeType nodeType,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int ordinal,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String text,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) Integer headingLevel,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String numberingLabel,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) SourceSpanView sourceSpan,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String rawStyleId) {
    }

    @Schema(description = "Location of a node in the source DOCX")
    public record SourceSpanView(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String partPath,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int bodyIndex,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) Integer paragraphIndex,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) Integer tableIndex,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) Integer rowIndex,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) Integer cellIndex,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) Integer runStart,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) Integer runEnd) {
    }
}
