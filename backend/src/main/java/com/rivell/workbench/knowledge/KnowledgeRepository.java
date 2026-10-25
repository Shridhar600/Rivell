package com.rivell.workbench.knowledge;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;

/**
 * Persistence boundary for the knowledge module. Implementations live in platform.
 * Every read method takes an organization id and must enforce it in SQL.
 */
public interface KnowledgeRepository {

    void insertArtifact(KnowledgeArtifact artifact);

    void updateDocumentTitle(UUID artifactId, String documentTitle);

    void updateIngestOutcome(
            UUID artifactId, String parserVersion, IngestStatus status, List<String> warnings);

    void updateArtifactAssessment(UUID artifactId, ArtifactTypeAssessment assessment);

    void updateParseCoverage(UUID artifactId, com.rivell.workbench.contracts.ParseCoverage coverage);

    /** Empty when the artifact predates the coverage check or the check could not run. */
    Optional<com.rivell.workbench.contracts.ParseCoverage> findParseCoverage(
            UUID artifactId, UUID organizationId);

    Optional<KnowledgeArtifact> findArtifact(UUID artifactId, UUID organizationId);

    /**
     * Newest-first artifact list. {@code limit} bounds the read; null keeps the
     * unbounded behaviour existing callers rely on.
     */
    List<KnowledgeArtifact> listArtifacts(UUID organizationId, Integer limit);

    Optional<KnowledgeSource> findSource(UUID artifactId, UUID organizationId);

    void insertNodes(UUID artifactId, List<KnowledgeSourceNode> nodes);

    void insertUnits(UUID artifactId, List<ExtractionUnit> units);

    void insertCandidates(UUID artifactId, List<KnowledgeCandidate> candidates);

    List<KnowledgeSourceNode> loadStructure(UUID artifactId, UUID organizationId);

    List<ExtractionUnit> loadUnits(UUID artifactId, UUID organizationId);

    Optional<ExtractionUnit> findUnit(UUID unitId, UUID organizationId);

    /** Every named unit that belongs to the organization: one read for a bundle's provenance. */
    List<ExtractionUnit> findUnits(Set<UUID> unitIds, UUID organizationId);

    /**
     * Records a person's record-kind correction on one part, once: false when the part already
     * carries a correction (or is not this organization's), and nothing is written then.
     */
    boolean overrideRecordKind(UUID unitId, UUID organizationId, KnowledgeRecordKind kind,
            UUID by, java.time.Instant at);

    List<KnowledgeCandidate> loadCandidates(UUID artifactId, UUID organizationId);

    /**
     * Every stored candidate, across organizations, together with the text its extraction unit
     * read. Used only by the one-time source-applicability migration: it needs the same two
     * inputs the compiler used, the candidate payload and, for a heading section, the unit text
     * that may state "Applies to:". This is intentionally not organization-scoped.
     */
    List<CandidateUnit> listCandidateUnitsForMigration();

    /** One candidate and the source text its unit read. */
    record CandidateUnit(
            KnowledgeCandidate candidate,
            ExtractionUnitKind unitKind,
            String unitContentText) {
    }

    /** Rewrites only the derived record-level scope; review state and timestamps are untouched. */
    boolean updateSourceContractTypes(
            UUID candidateId, UUID organizationId, Set<String> types);

    /** Whether the named one-time data migration has completed in this database. */
    boolean dataMigrationCompleted(String migrationKey);

    /** Records a completed one-time data migration; a repeated record is harmless. */
    void recordDataMigration(String migrationKey, String detail, Instant completedAt);

    Optional<KnowledgeCandidate> findCandidate(UUID candidateId, UUID organizationId);

    /**
     * Human concept correction (DW-017); pre-publication only. Persists the
     * correction, the resulting review status (an APPROVED candidate demotes to
     * EDITED for re-approval), and the recomputed analysis outcome. Returns false
     * when the expected status no longer holds (concurrent transition).
     */
    boolean updateConceptCorrection(UUID candidateId, UUID organizationId,
            KnowledgeCandidate updated, ReviewStatus expectedStatus,
            UUID correctedBy, java.time.Instant updatedAt);

    /**
     * Persists a pre-publication review transition together with the candidate's derived
     * conditions. Callers that do not re-derive conditions pass the candidate's current values,
     * so conditions are only ever changed deliberately (see CandidateReviewService.edit).
     *
     * <p>This is a compare-and-set on the review status and the caller's responsibility is to
     * check the answer: returns false when the expected status no longer held, which means
     * somebody else moved the candidate first. Reporting success for a transition that wrote
     * nothing would let a caller believe it holds a state it does not - the publication path
     * would commit items for a candidate that is still EDITED.
     */
    boolean updateReviewState(
            UUID candidateId,
            UUID organizationId,
            ReviewStatus expectedStatus,
            ReviewStatus reviewStatus,
            String editedContentText,
            String editedTitle,
            CandidatePayload editedPayload,
            SemanticFields editedSemanticFields,
            ApplicabilityScope scope,
            UUID supersedingCandidateId,
            String reviewNote,
            Instant reviewedAt,
            Instant updatedAt,
            List<ConflictSignal> conflictSignals,
            ReviewCategory reviewCategory,
            Set<ReviewReason> reviewReasons);

    List<KnowledgeCandidate> findPublishedCandidates(UUID organizationId);

    List<PublishedCandidate> findPublishedCandidatesAsOf(PublishedKnowledgeQuery query);

    /**
     * The published playbooks and clause libraries that apply to a declared context, by the same
     * contract-type and scope rule as {@link #findPublishedCandidatesAsOf(PublishedKnowledgeQuery)}.
     */
    List<ApplicableKnowledge> findApplicableKnowledge(ApplicableKnowledgeQuery query);

    /** System-level read (background flows): orgs that have published knowledge. */
    List<UUID> organizationsWithPublishedKnowledge();

    /** Latest publication number for the org, 0 when nothing is published. */
    long maxPublicationNumber(UUID organizationId);

    /** Whether a publication with the given number exists in the org. */
    boolean publicationNumberExists(UUID organizationId, long publicationNumber);

    void insertPublication(KnowledgePublication publication);

    /**
     * Publications newest first. {@code limit} bounds the read; null keeps the unbounded
     * behaviour existing callers rely on.
     */
    List<KnowledgePublication> listPublications(UUID organizationId, Integer limit);

    Optional<KnowledgePublication> findPublication(UUID publicationId, UUID organizationId);

    List<PublicationItem> loadPublicationItems(UUID publicationId, UUID organizationId);

    long nextPublicationNumber(UUID organizationId);
}
