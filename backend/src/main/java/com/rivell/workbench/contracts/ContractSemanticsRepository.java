package com.rivell.workbench.contracts;

import java.util.Optional;
import java.util.UUID;

/**
 * Persistence boundary for the contract semantic model. Implementations live in
 * platform. Every method takes an organization id and must enforce it in SQL;
 * semantic rows are bound to a contract version of the same organization.
 */
public interface ContractSemanticsRepository {

    void insertSemanticsRow(ContractSemantics semantics);

    boolean updateStatus(UUID contractVersionId, UUID organizationId, EnrichmentStatus status);

    Optional<EnrichmentStatus> findStatus(UUID contractVersionId, UUID organizationId);

    /** Full machine-layer replace inside one transaction; the service merges corrections. */
    void replaceSemantics(ContractSemantics semantics);

    Optional<ContractSemantics> find(UUID contractVersionId, UUID organizationId);

    boolean updatePartyCorrection(
            UUID partyId, UUID organizationId, PartyCorrection correction, UUID correctedBy);

    boolean updateClauseCorrection(
            UUID clauseId, UUID organizationId, FactCorrection correction, UUID correctedBy);
}
