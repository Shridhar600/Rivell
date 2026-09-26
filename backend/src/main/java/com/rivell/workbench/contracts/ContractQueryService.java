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

    private final ContractRepository repository;
    private final IdentityProvider identityProvider;
    private final AuthorizationService authorizationService;

    public ContractQueryService(
            ContractRepository repository,
            IdentityProvider identityProvider,
            AuthorizationService authorizationService) {
        this.repository = repository;
        this.identityProvider = identityProvider;
        this.authorizationService = authorizationService;
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
