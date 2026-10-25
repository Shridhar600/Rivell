package com.rivell.workbench.knowledge;

import java.util.List;
import java.util.Optional;
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

    private static final int MAX_LIST_LIMIT = 100;

    private final KnowledgeRepository repository;
    private final IdentityProvider identityProvider;
    private final AuthorizationService authorizationService;
    private final java.time.Clock clock;

    public KnowledgeQueryService(
            KnowledgeRepository repository,
            IdentityProvider identityProvider,
            AuthorizationService authorizationService,
            java.time.Clock clock) {
        this.repository = repository;
        this.identityProvider = identityProvider;
        this.authorizationService = authorizationService;
        this.clock = clock;
    }

    /**
     * Artifact list for the caller. {@code limit} is optional: absent, the whole corpus is
     * returned exactly as before this parameter existed.
     */
    public List<KnowledgeArtifact> listArtifacts(Integer limit) {
        requireRead();
        return repository.listArtifacts(organizationId(), boundedLimit(limit));
    }

    public KnowledgeArtifact getArtifact(UUID artifactId) {
        requireRead();
        return repository.findArtifact(artifactId, organizationId())
                .orElseThrow(() -> new KnowledgeArtifactNotFoundException(artifactId));
    }

    /** The artifact's coverage report; empty when it predates the check. */
    public Optional<com.rivell.workbench.contracts.ParseCoverage> getParseCoverage(UUID artifactId) {
        requireRead();
        assertArtifactExists(artifactId);
        return repository.findParseCoverage(artifactId, organizationId());
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

    /**
     * Publication list for the caller. {@code limit} is optional: absent, every
     * publication is returned exactly as before this parameter existed.
     */
    public List<KnowledgePublication> listPublications(Integer limit) {
        requireRead();
        return repository.listPublications(organizationId(), boundedLimit(limit));
    }

    /**
     * Validates an optional list bound. Absent means unbounded, which is the behaviour
     * that predates the parameter and is kept for existing callers; a supplied value must
     * be positive and is capped so a caller cannot request an arbitrary page.
     */
    private static Integer boundedLimit(Integer limit) {
        if (limit == null) {
            return null;
        }
        if (limit < 1) {
            throw new IllegalArgumentException("The page size must be 1 or more.");
        }
        return Math.min(limit, MAX_LIST_LIMIT);
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

    /**
     * The playbooks and clause libraries that apply to the caller's chosen context. The as-of
     * publication is the organization's latest, so the preview names what a review created now
     * would freeze.
     */
    public List<ApplicableKnowledge> applicableKnowledge(
            String contractType, String contractPackKey, String partyPerspective,
            String paperSource, String jurisdiction, java.time.LocalDate effectiveOn) {
        requireRead();
        var organizationId = organizationId();
        var asOfDate = effectiveOn == null ? java.time.LocalDate.now(clock) : effectiveOn;
        return repository.findApplicableKnowledge(new ApplicableKnowledgeQuery(
                organizationId, repository.maxPublicationNumber(organizationId), contractType,
                contractPackKey, partyPerspective, paperSource, jurisdiction, asOfDate));
    }

    /**
     * System-level read for review creation: the caller is authorized for the review, and the
     * snapshot must freeze the same published set the analysis will use.
     */
    public List<ApplicableKnowledge> applicableKnowledgeForOrganization(
            ApplicableKnowledgeQuery query) {
        return repository.findApplicableKnowledge(query);
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

    /** System-level read of the named units, keyed by id: one query for a bundle's provenance. */
    public java.util.Map<UUID, ExtractionUnit> findUnitsForOrganization(
            UUID organizationId, java.util.Set<UUID> unitIds) {
        return repository.findUnits(unitIds, organizationId).stream()
                .collect(java.util.stream.Collectors.toMap(
                        ExtractionUnit::id, unit -> unit));
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
