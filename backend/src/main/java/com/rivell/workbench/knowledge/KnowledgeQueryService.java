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

    /**
     * Explicit-organization read for system-triggered flows (e.g. post-commit
     * indexing listeners) where the caller's identity context must not be assumed.
     */
    public java.util.Optional<KnowledgeCandidate> getCandidateForOrganization(
            UUID organizationId, UUID candidateId) {
        return repository.findCandidate(candidateId, organizationId);
    }

    public java.util.Optional<ExtractionUnit> findUnit(UUID unitId) {
        requireRead();
        return repository.findUnit(unitId, organizationId());
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

    /** Latest publication number for the caller's organization, 0 when none. */
    public long maxPublicationNumber() {
        requireRead();
        return repository.maxPublicationNumber(organizationId());
    }

    /**
     * System-level read for background flows (e.g. retrieval index reconciliation):
     * explicit organization, no caller-identity requirement. Never expose through
     * product APIs without an authorization check.
     */
    public List<PublishedCandidate> listPublishedCandidatesForOrganization(
            PublishedKnowledgeQuery query) {
        return repository.findPublishedCandidatesAsOf(query);
    }

    /** System-level read: organizations that have published knowledge. */
    public List<UUID> organizationsWithPublishedKnowledge() {
        return repository.organizationsWithPublishedKnowledge();
    }

    /** System-level read: latest publication number for the explicit org. */
    public long maxPublicationNumberForOrganization(UUID organizationId) {
        return repository.maxPublicationNumber(organizationId);
    }

    /** Whether a publication with the given number exists in the caller's organization. */
    /** System-level existence check for background flows (explicit organization). */
    public boolean publicationNumberExistsForOrganization(
            UUID organizationId, long publicationNumber) {
        return repository.publicationNumberExists(organizationId, publicationNumber);
    }

    /** System-level unit read for background flows (explicit organization). */
    public java.util.Optional<ExtractionUnit> findUnitForOrganization(
            UUID organizationId, UUID unitId) {
        return repository.findUnit(unitId, organizationId);
    }

    public boolean publicationNumberExists(long publicationNumber) {
        requireRead();
        return repository.publicationNumberExists(organizationId(), publicationNumber);
    }

    public KnowledgePublication getPublication(UUID publicationId) {
        requireRead();
        var publication = repository.findPublication(publicationId, organizationId())
                .orElseThrow(() -> new KnowledgeArtifactNotFoundException(publicationId));
        return publication;
    }

    /**
     * Bounded, supersession-aware published-knowledge lookup for retrieval
     * (Architecture Decision #8.1, DW-010). The query carries the organization id
     * explicitly because retrieval planning is a server-side flow; the caller must
     * still be authorized for knowledge reads.
     */
    public List<PublishedCandidate> findPublishedCandidatesAsOf(PublishedKnowledgeQuery query) {
        requireRead();
        var identity = identityProvider.currentIdentity();
        if (!query.organizationId().equals(identity.organizationId())) {
            throw new UnauthorizedKnowledgeAccessException(identity.userId());
        }
        return repository.findPublishedCandidatesAsOf(query);
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
