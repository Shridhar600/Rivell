package com.rivell.workbench.knowledge;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence boundary for the knowledge module. Implementations live in platform.
 * Every read method takes an organization id and must enforce it in SQL.
 */
public interface KnowledgeRepository {

    void insertArtifact(KnowledgeArtifact artifact);

    void updateIngestOutcome(
            UUID artifactId, String parserVersion, IngestStatus status, List<String> warnings);

    Optional<KnowledgeArtifact> findArtifact(UUID artifactId, UUID organizationId);

    List<KnowledgeArtifact> listArtifacts(UUID organizationId);

    Optional<KnowledgeSource> findSource(UUID artifactId, UUID organizationId);

    void insertNodes(UUID artifactId, List<KnowledgeSourceNode> nodes);

    void insertUnits(UUID artifactId, List<ExtractionUnit> units);

    void insertCandidates(UUID artifactId, List<KnowledgeCandidate> candidates);

    List<KnowledgeSourceNode> loadStructure(UUID artifactId, UUID organizationId);

    List<ExtractionUnit> loadUnits(UUID artifactId, UUID organizationId);

    List<KnowledgeCandidate> loadCandidates(UUID artifactId, UUID organizationId);

    Optional<KnowledgeCandidate> findCandidate(UUID candidateId, UUID organizationId);

    void updateReviewState(
            UUID candidateId,
            ReviewStatus reviewStatus,
            String editedContentText,
            CandidatePayload editedPayload,
            String reviewNote,
            java.time.Instant reviewedAt,
            java.time.Instant updatedAt);

    List<KnowledgeCandidate> findPublishedCandidates(UUID organizationId);

    void insertPublication(KnowledgePublication publication);

    List<KnowledgePublication> listPublications(UUID organizationId);

    Optional<KnowledgePublication> findPublication(UUID publicationId, UUID organizationId);

    List<PublicationItem> loadPublicationItems(UUID publicationId, UUID organizationId);

    long nextPublicationNumber(UUID organizationId);
}
