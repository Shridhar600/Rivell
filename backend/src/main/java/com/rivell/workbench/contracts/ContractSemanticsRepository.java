package com.rivell.workbench.contracts;

import java.util.Optional;
import java.util.UUID;

/**
 * Persistence boundary for the contract semantic model. Implementations live in
 * platform. Every method takes an organization id and must enforce it in SQL;
 * semantic rows are bound to a contract version of the same organization. The
 * header row identity is stable per version: {@link #replaceSemantics} keeps the
 * existing id and replaces the machine layer beneath it.
 */
public interface ContractSemanticsRepository {

    /**
     * Atomically transitions the version's semantics row to ENRICHING and applies the
     * (possibly new) review profile. Returns false when the row is already ENRICHING
     * and not stale; child rows are untouched.
     */
    boolean beginEnrichment(
            UUID contractVersionId, UUID organizationId, ReviewProfile profile);

    /** Full machine-layer replace in one transaction; the service merges corrections. */
    void replaceSemantics(ContractSemantics semantics);

    Optional<EnrichmentStatus> findStatus(UUID contractVersionId, UUID organizationId);

    Optional<ContractSemantics> find(UUID contractVersionId, UUID organizationId);

    boolean updatePartyCorrection(
            UUID partyId, UUID organizationId, PartyCorrection correction, UUID correctedBy);

    boolean updateClauseCorrection(
            UUID clauseId, UUID organizationId, FactCorrection correction, UUID correctedBy);
}
