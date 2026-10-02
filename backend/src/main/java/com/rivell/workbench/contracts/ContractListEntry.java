package com.rivell.workbench.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * One contract with its latest version state, as a list row. Composition of read models
 * happens in the query service; the API layer maps this onto response DTOs.
 */
public record ContractListEntry(
        UUID contractId, String title, Instant createdAt,
        ContractVersionSummary latestVersion) {
}
