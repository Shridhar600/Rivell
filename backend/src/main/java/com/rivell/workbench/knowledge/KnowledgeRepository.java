package com.rivell.workbench.knowledge;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

/**
 * Persistence boundary for the knowledge module. Implementations live in platform.
 * Every read method takes an organization id and must enforce it in SQL.
 */
public interface KnowledgeRepository {

    void insertArtifact(KnowledgeArtifact artifact);

    void updateIngestOutcome(
            UUID artifactId, String parserVersion, IngestStatus status, List<String> warnings);

    void updateArtifactAssessment(UUID artifactId, ArtifactTypeAssessment assessment);

    Optional<KnowledgeArtifact> findArtifact(UUID artifactId, UUID organizationId);

    List<KnowledgeArtifact> listArtifacts(UUID organizationId);

    Optional<KnowledgeSource> findSource(UUID artifactId, UUID organizationId);

    void insertNodes(UUID artifactId, List<KnowledgeSourceNode> nodes);

    void insertUnits(UUID artifactId, List<ExtractionUnit> units);

    void insertCandidates(UUID artifactId, List<KnowledgeCandidate> candidates);

    List<KnowledgeSourceNode> loadStructure(UUID artifactId, UUID organizationId);

    List<ExtractionUnit> loadUnits(UUID artifactId, UUID organizationId);

    Optional<ExtractionUnit> findUnit(UUID unitId, UUID organizationId);

    List<KnowledgeCandidate> loadCandidates(UUID artifactId, UUID organizationId);

    Optional<KnowledgeCandidate> findCandidate(UUID candidateId, UUID organizationId);

    /** Human LegalConcept correction (DW-017); pre-publication only. */
    boolean updateConceptCorrection(UUID candidateId, UUID organizationId,
            String conceptCorrection, UUID correctedBy, java.time.Instant updatedAt);

    void updateReviewState(
            UUID candidateId,
            UUID organizationId,
            ReviewStatus expectedStatus,
            ReviewStatus reviewStatus,
            String editedContentText,
            CandidatePayload editedPayload,
            SemanticFields editedSemanticFields,
            ApplicabilityScope scope,
            UUID supersedingCandidateId,
            String reviewNote,
            Instant reviewedAt,
            Instant updatedAt);

    List<KnowledgeCandidate> findPublishedCandidates(UUID organizationId);

    List<PublishedCandidate> findPublishedCandidatesAsOf(PublishedKnowledgeQuery query);

    /** System-level read (background flows): orgs that have published knowledge. */
    List<UUID> organizationsWithPublishedKnowledge();

    /** Latest publication number for the org, 0 when nothing is published. */
    long maxPublicationNumber(UUID organizationId);

    /** Whether a publication with the given number exists in the org. */
    boolean publicationNumberExists(UUID organizationId, long publicationNumber);

    void insertPublication(KnowledgePublication publication);

    List<KnowledgePublication> listPublications(UUID organizationId);

    Optional<KnowledgePublication> findPublication(UUID publicationId, UUID organizationId);

    List<PublicationItem> loadPublicationItems(UUID publicationId, UUID organizationId);

    long nextPublicationNumber(UUID organizationId);
}
