package com.rivell.workbench.knowledge;

import java.util.List;
import java.util.Objects;

/**
 * Deterministic structural extraction of a knowledge source: the parsed node tree plus
 * quality warnings. The knowledge compiler turns this into extraction units and
 * candidates; interpretation never happens inside platform adapters.
 */
public record StructureExtraction(
        String parserVersion,
        IngestStatus status,
        List<String> warnings,
        List<KnowledgeSourceNode> nodes,
        com.rivell.workbench.contracts.ParseCoverage coverage) {

    /** Without a coverage report (failed extraction, or a check that could not run). */
    public StructureExtraction(String parserVersion, IngestStatus status, List<String> warnings,
            List<KnowledgeSourceNode> nodes) {
        this(parserVersion, status, warnings, nodes, null);
    }


    public StructureExtraction {
        if (parserVersion == null || parserVersion.isBlank()) {
            throw new IllegalArgumentException("Parser version must not be blank");
        }
        Objects.requireNonNull(status, "status");
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }

    public static StructureExtraction failed(String parserVersion, List<String> warnings) {
        Objects.requireNonNull(parserVersion);
        return new StructureExtraction(parserVersion, IngestStatus.FAILED, warnings, List.of());
    }
}
