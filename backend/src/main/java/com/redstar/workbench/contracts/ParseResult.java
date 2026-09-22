package com.redstar.workbench.contracts;

import java.util.List;
import java.util.Objects;

public record ParseResult(
        String parserVersion,
        ParseStatus status,
        List<String> warnings,
        List<DocumentNode> nodes) {

    public ParseResult {
        if (parserVersion == null || parserVersion.isBlank()) {
            throw new IllegalArgumentException("Parser version must not be blank");
        }
        Objects.requireNonNull(status, "status");
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }
}
