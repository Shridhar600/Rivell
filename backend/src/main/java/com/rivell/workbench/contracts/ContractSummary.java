package com.rivell.workbench.contracts;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Composed read model: a contract together with its latest version state. Assembled by
 * the query service; the API layer maps it onto response DTOs.
 */
public record ContractSummary(Contract contract, ContractVersionSummary latestVersion) {

    public ContractSummary {
        Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(latestVersion, "latestVersion");
    }
}
