package com.rivell.workbench.contracts;

import java.util.Optional;
import java.util.UUID;
import java.util.List;
import java.time.Instant;

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

    /** Heading node ids of one immutable contract version (cheap readiness reads). */
    java.util.List<UUID> findHeadingNodeIds(UUID versionId);

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
            UUID contractId, UUID organizationId, String title, Instant createdAt);

    void insertVersion(
            UUID versionId,
            UUID contractId,
            UUID sourceArtifactId,
            int versionNumber,
            ContractRole role,
            String parserVersion,
            ParseStatus parseStatus,
            List<String> parseWarnings,
            Instant createdAt);

    void updateVersionParseOutcome(
            UUID versionId, String parserVersion, ParseStatus status, List<String> warnings);

    void updateContractTitle(UUID contractId, String title);

    void insertNodes(UUID contractVersionId, List<DocumentNode> nodes);
}
