package com.redstar.workbench.contracts;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ContractVersion(
        UUID id,
        UUID contractId,
        int versionNumber,
        UUID sourceArtifactId,
        ContractRole role,
        String parserVersion,
        ParseStatus parseStatus,
        List<String> parseWarnings,
        Instant createdAt) {

    public ContractVersion {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(contractId, "contractId");
        if (versionNumber < 1) {
            throw new IllegalArgumentException("Version number must start at 1");
        }
        Objects.requireNonNull(sourceArtifactId, "sourceArtifactId");
        Objects.requireNonNull(role, "role");
        if (parserVersion == null || parserVersion.isBlank()) {
            throw new IllegalArgumentException("Parser version must not be blank");
        }
        Objects.requireNonNull(parseStatus, "parseStatus");
        parseWarnings = parseWarnings == null ? List.of() : List.copyOf(parseWarnings);
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
