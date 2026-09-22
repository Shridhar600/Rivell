package com.redstar.workbench.contracts;

import java.util.UUID;

import com.redstar.workbench.organization.AuthorizationService;
import com.redstar.workbench.organization.IdentityProvider;
import com.redstar.workbench.organization.Permissions;

/**
 * Organization-scoped contract reads. All access goes through the current identity and
 * the {@link AuthorizationService}; cross-organization access is indistinguishable from
 * a missing contract.
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
