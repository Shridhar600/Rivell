package com.rivell.workbench.contracts;

import java.util.UUID;

/**
 * Read model describing the immutable source of a contract version.
 */
public record ContractVersionSummary(
        UUID versionId,
        UUID contractId,
        int versionNumber,
        ParseStatus parseStatus,
        java.util.List<String> parseWarnings,
        String parserVersion,
        String sha256,
        String filename,
        int nodeCount) {
}
