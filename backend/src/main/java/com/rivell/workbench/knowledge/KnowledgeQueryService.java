package com.rivell.workbench.knowledge;

import java.util.List;
import java.util.UUID;

import com.rivell.workbench.organization.AuthorizationService;
import com.rivell.workbench.organization.IdentityProvider;
import com.rivell.workbench.organization.Permissions;

/**
 * Read paths for knowledge artifacts, structure, extraction units, candidates and
 * publications. All reads are organization-scoped; cross-organization access is
 * indistinguishable from a missing object (M1 tenant pattern).
 */
public class KnowledgeQueryService {

    private final KnowledgeRepository repository;
    private final IdentityProvider identityProvider;
    private final AuthorizationService authorizationService;

    public KnowledgeQueryService(
            KnowledgeRepository repository,
            IdentityProvider identityProvider,
            AuthorizationService authorizationService) {
        this.repository = repository;
        this.identityProvider = identityProvider;
        this.authorizationService = authorizationService;
    }

    public List<KnowledgeArtifact> listArtifacts() {
        requireRead();
        return repository.listArtifacts(organizationId());
    }

    public KnowledgeArtifact getArtifact(UUID artifactId) {
        requireRead();
        return repository.findArtifact(artifactId, organizationId())
                .orElseThrow(() -> new KnowledgeArtifactNotFoundException(artifactId));
    }

    public List<KnowledgeSourceNode> getStructure(UUID artifactId) {
        requireRead();
        assertArtifactExists(artifactId);
        return repository.loadStructure(artifactId, organizationId());
    }

    public List<ExtractionUnit> getUnits(UUID artifactId) {
        requireRead();
        assertArtifactExists(artifactId);
        return repository.loadUnits(artifactId, organizationId());
    }

    public List<KnowledgeCandidate> getCandidates(UUID artifactId) {
        requireRead();
        assertArtifactExists(artifactId);
        return repository.loadCandidates(artifactId, organizationId());
    }

    public KnowledgeCandidate getCandidate(UUID candidateId) {
        requireRead();
        return repository.findCandidate(candidateId, organizationId())
                .orElseThrow(() -> new KnowledgeCandidateNotFoundException(candidateId));
    }

    public KnowledgeSource getSource(UUID artifactId) {
        requireRead();
        return repository.findSource(artifactId, organizationId())
                .orElseThrow(() -> new KnowledgeArtifactNotFoundException(artifactId));
    }

    public List<KnowledgePublication> listPublications() {
        requireRead();
        return repository.listPublications(organizationId());
    }

    public KnowledgePublication getPublication(UUID publicationId) {
        requireRead();
        var publication = repository.findPublication(publicationId, organizationId())
                .orElseThrow(() -> new KnowledgeArtifactNotFoundException(publicationId));
        return publication;
    }

    private void assertArtifactExists(UUID artifactId) {
        if (repository.findArtifact(artifactId, organizationId()).isEmpty()) {
            throw new KnowledgeArtifactNotFoundException(artifactId);
        }
    }

    private UUID organizationId() {
        return identityProvider.currentIdentity().organizationId();
    }

    private void requireRead() {
        var identity = identityProvider.currentIdentity();
        if (!authorizationService.isAuthorized(identity, Permissions.KNOWLEDGE_READ)) {
            throw new UnauthorizedKnowledgeAccessException(identity.userId());
        }
    }
}
