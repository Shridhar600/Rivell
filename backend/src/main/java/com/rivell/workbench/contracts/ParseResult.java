package com.rivell.workbench.contracts;

import java.util.List;
import java.util.Objects;

/** {@code coverage} is null when the source could not be checked (e.g. a failed parse). */
public record ParseResult(
        String parserVersion,
        ParseStatus status,
        List<String> warnings,
        List<DocumentNode> nodes,
        ParseCoverage coverage) {

    public ParseResult(String parserVersion, ParseStatus status, List<String> warnings,
            List<DocumentNode> nodes) {
        this(parserVersion, status, warnings, nodes, null);
    }

    public ParseResult {
        if (parserVersion == null || parserVersion.isBlank()) {
            throw new IllegalArgumentException("Parser version must not be blank");
        }
        Objects.requireNonNull(status, "status");
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }
}
