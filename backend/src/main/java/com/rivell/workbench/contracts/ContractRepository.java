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

    /**
     * Bounded newest-first organization-scoped contract list. Implementations read
     * {@code limit + 1} rows so callers can detect more rows and mint a cursor.
     * {@code afterCreatedAt}/{@code afterId} resume after a previous page and are both
     * null for the first page. Each row carries the contract's latest version, projected
     * by the same rule the single-contract read uses.
     *
     * <p>This is enumeration only. It adds no version write path: an upload still mints a
     * new contract.
     */
    List<ContractListEntry> findContracts(
            UUID organizationId, java.time.Instant afterCreatedAt, UUID afterId, int limit);

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
            UUID versionId, String parserVersion, ParseStatus status, List<String> warnings,
            ParseCoverage coverage);

    /** Empty when the version was parsed before the coverage check existed or it could not run. */
    Optional<ParseCoverage> findParseCoverage(UUID versionId);

    void updateContractTitle(UUID contractId, String title);

    void insertNodes(UUID contractVersionId, List<DocumentNode> nodes);
}
