package com.rivell.workbench.contracts;

import java.util.UUID;

import com.rivell.workbench.organization.AuthorizationService;
import com.rivell.workbench.organization.IdentityProvider;
import com.rivell.workbench.organization.Permissions;

/**
 * Organization-scoped contract reads. All access goes through the current identity and
 * the {@link AuthorizationService}; cross-organization access is indistinguishable from
 * a missing contract. Composition of read models happens here, not in controllers.
 */
public class ContractQueryService {

    private static final int DEFAULT_PAGE_LIMIT = 20;
    private static final int MAX_PAGE_LIMIT = 100;

    private final ContractRepository repository;
    private final ContractSemanticsRepository semanticsRepository;
    private final IdentityProvider identityProvider;
    private final AuthorizationService authorizationService;

    public ContractQueryService(
            ContractRepository repository,
            ContractSemanticsRepository semanticsRepository,
            IdentityProvider identityProvider,
            AuthorizationService authorizationService) {
        this.repository = repository;
        this.semanticsRepository = semanticsRepository;
        this.identityProvider = identityProvider;
        this.authorizationService = authorizationService;
    }

    /**
     * Bounded newest-first page of the organization's contracts, with the opaque position
     * to resume from. Enumeration only: it adds no version write path, and an upload
     * still mints a new contract.
     */
    public ContractListPage listContractsPage(Integer limit, String cursorToken) {
        requireReadPermission();
        var bounded = boundedLimit(limit);
        var after = com.rivell.workbench.foundation.PageCursor.decode(cursorToken);
        var rows = repository.findContracts(currentOrganizationId(),
                after == null ? null : after.createdAt(),
                after == null ? null : after.id(),
                bounded);
        var truncated = rows.size() > bounded;
        var page = java.util.List.copyOf(rows.stream().limit(bounded).toList());
        var nextCursor = truncated && !page.isEmpty()
                ? com.rivell.workbench.foundation.PageCursor.encode(
                        new com.rivell.workbench.foundation.PageCursor(
                                page.get(page.size() - 1).createdAt(),
                                page.get(page.size() - 1).contractId()))
                : null;
        return new ContractListPage(page, nextCursor);
    }

    /** One bounded page of contracts plus its opaque resume position. */
    public record ContractListPage(java.util.List<ContractListEntry> items, String nextCursor) {
    }

    private static int boundedLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_PAGE_LIMIT;
        }
        if (limit < 1) {
            throw new IllegalArgumentException("The page size must be 1 or more.");
        }
        return Math.min(limit, MAX_PAGE_LIMIT);
    }

    public ContractSummary getContractSummary(UUID contractId) {
        var contract = getContract(contractId);
        var latestVersion = getLatestVersionSummary(contractId);
        return new ContractSummary(contract, latestVersion);
    }

    public Contract getContract(UUID contractId) {
        requireReadPermission();
        return repository
                .findContract(contractId, currentOrganizationId())
                .orElseThrow(() -> new ContractNotFoundException(contractId));
    }

    public ContractVersionSummary getLatestVersionSummary(UUID contractId) {
        requireReadPermission();
        return repository
                .findLatestVersionSummary(contractId, currentOrganizationId())
                .orElseThrow(() -> new ContractNotFoundException(contractId));
    }

    public ContractStructure getStructure(UUID contractId, int versionNumber) {
        var summary = getVersionSummary(contractId, versionNumber);
        return repository.loadStructure(summary.versionId());
    }

    /** The version's coverage report; empty when it was parsed before the check existed. */
    public java.util.Optional<ParseCoverage> getParseCoverage(UUID contractId, int versionNumber) {
        var summary = getVersionSummary(contractId, versionNumber);
        return repository.findParseCoverage(summary.versionId());
    }

    public ContractVersionSummary getVersionSummary(UUID contractId, int versionNumber) {
        requireReadPermission();
        return repository
                .findVersionSummary(contractId, versionNumber, currentOrganizationId())
                .orElseThrow(() -> new ContractVersionNotFoundException(contractId, versionNumber));
    }

    /** System-level read for background flows (explicit organization, no authorization). */
    public ContractVersionSummary getVersionSummaryForOrganization(
            UUID organizationId, UUID contractId, int versionNumber) {
        return repository
                .findVersionSummary(contractId, versionNumber, organizationId)
                .orElseThrow(() -> new ContractVersionNotFoundException(contractId, versionNumber));
    }

    /** System-level structure read for flows whose organization is already resolved. */
    public ContractStructure getStructureForOrganization(
            UUID organizationId, UUID contractId, int versionNumber) {
        var summary = getVersionSummaryForOrganization(organizationId, contractId, versionNumber);
        return repository.loadStructure(summary.versionId());
    }

    /** System-level heading-id read: the cheap subset readiness validation needs. */
    public java.util.Set<UUID> headingNodeIdsForOrganization(
            UUID organizationId, UUID contractId, int versionNumber) {
        var summary = getVersionSummaryForOrganization(organizationId, contractId, versionNumber);
        return java.util.Set.copyOf(repository.findHeadingNodeIds(summary.versionId()));
    }

    /** System-level: headings whose clause a tracked change cannot replace or delete. */
    public java.util.Set<UUID> untrackableHeadingsForOrganization(
            UUID organizationId, UUID contractId, int versionNumber) {
        return new ContractRegionSegmenter().untrackableHeadings(
                getStructureForOrganization(organizationId, contractId, versionNumber).nodes());
    }

    /** System-level contract read for flows whose organization is already resolved. */
    public Contract getContractForOrganization(UUID organizationId, UUID contractId) {
        return repository
                .findContract(contractId, organizationId)
                .orElseThrow(() -> new ContractNotFoundException(contractId));
    }

    /** System-level source read for flows whose organization is already resolved. */
    public ContractSource getSourceForOrganization(
            UUID organizationId, UUID contractId, int versionNumber) {
        var summary = getVersionSummaryForOrganization(organizationId, contractId, versionNumber);
        return repository
                .findSourceForVersion(summary.versionId(), organizationId)
                .orElseThrow(() -> new ContractVersionNotFoundException(contractId, versionNumber));
    }

    /** System-level semantic read; empty when the version has no semantic layer yet. */
    public java.util.Optional<ContractSemantics> getSemanticsForOrganization(
            UUID organizationId, UUID contractId, int versionNumber) {
        var summary = getVersionSummaryForOrganization(organizationId, contractId, versionNumber);
        return semanticsRepository.find(summary.versionId(), organizationId);
    }

    public ContractSource getSourceForDownload(UUID contractId, int versionNumber) {
        var summary = getVersionSummary(contractId, versionNumber);
        return repository
                .findSourceForVersion(summary.versionId(), currentOrganizationId())
                .orElseThrow(() -> new ContractVersionNotFoundException(contractId, versionNumber));
    }

    private void requireReadPermission() {
        var identity = identityProvider.currentIdentity();
        if (!authorizationService.isAuthorized(identity, Permissions.CONTRACT_READ)) {
            throw new UnauthorizedContractAccessException(identity.userId());
        }
    }

    private UUID currentOrganizationId() {
        return identityProvider.currentIdentity().organizationId();
    }
}
