package com.redstar.workbench.contracts;

import java.util.Optional;
import java.util.UUID;

/**
 * Persistence boundary for the contracts module. Implementations live in platform.
 * All read methods take an organization id and must enforce it in SQL.
 */
public interface ContractRepository {

    Optional<Contract> findContract(UUID contractId, UUID organizationId);

    Optional<ContractVersionSummary> findLatestVersionSummary(UUID contractId, UUID organizationId);

    Optional<ContractVersionSummary> findVersionSummary(
            UUID contractId, int versionNumber, UUID organizationId);

    ContractStructure loadStructure(UUID versionId);

    Optional<ContractSource> findSourceForVersion(UUID versionId, UUID organizationId);

    void insertSourceArtifact(
            UUID artifactId,
            UUID organizationId,
            String filename,
            String contentType,
            long sizeBytes,
            String sha256,
            String storageKey);

    void insertContract(
            UUID contractId, UUID organizationId, String title, java.time.Instant createdAt);

    void insertVersion(
            UUID versionId,
            UUID contractId,
            UUID sourceArtifactId,
            int versionNumber,
            ContractRole role,
            String parserVersion,
            ParseStatus parseStatus,
            java.util.List<String> parseWarnings,
            java.time.Instant createdAt);

    void updateVersionParseOutcome(
            UUID versionId, String parserVersion, ParseStatus status, java.util.List<String> warnings);

    void updateContractTitle(UUID contractId, String title);

    void insertNodes(UUID contractVersionId, java.util.List<DocumentNode> nodes);
}
