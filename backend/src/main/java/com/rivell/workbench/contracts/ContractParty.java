package com.rivell.workbench.contracts;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A real-world legal entity as it appears in THIS agreement. Roles carry three
 * layers: machine interpretation, human correction, and the effective view
 * (correction wins). Provenance points at the exact source nodes.
 */
public record ContractParty(
        UUID id,
        UUID contractVersionId,
        String rawName,
        String normalizedName,
        List<String> aliases,
        List<Interpretation> interpretations,
        List<PartyRole> machineRoles,
        PartyCorrection correction,
        UUID correctedBy) {

    public ContractParty {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(contractVersionId, "contractVersionId");
        if (rawName == null || rawName.isBlank()) {
            throw new IllegalArgumentException("Party name must not be blank");
        }
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        interpretations = interpretations == null ? List.of() : List.copyOf(interpretations);
        machineRoles = machineRoles == null ? List.of() : List.copyOf(machineRoles);
        Objects.requireNonNull(correction, "correction");
    }

    /** Effective roles: human correction wins; machine proposal preserved underneath. */
    public List<PartyRole> effectiveRoles() {
        return correction.corrected() ? correction.roles() : machineRoles;
    }

    public List<PartyRole> machineRoles() {
        return machineRoles;
    }

    public boolean roleCorrected() {
        return correction.corrected();
    }

    public ContractParty withCorrection(PartyCorrection correction, UUID correctedBy) {
        return new ContractParty(id, contractVersionId, rawName, normalizedName, aliases,
                interpretations, machineRoles, correction, correctedBy);
    }
}
