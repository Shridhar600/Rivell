package com.rivell.workbench.retrieval;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence boundary for retrieval-owned derived data (evidence index + traces).
 * Implementations live in platform. Every method is organization scoped in SQL.
 */
public interface RetrievalRepository {

    void upsertIndexRow(EvidenceIndexRow row);

    void persistEmbedding(UUID organizationId, UUID candidateId, String contentHash,
            String indexVersion, String embeddingModel, int embeddingDims,
            String vectorLiteral);

    void markSuperseded(UUID organizationId, UUID candidateId, long supersededAtPublication);

    Optional<EvidenceIndexRow> findIndexRow(UUID organizationId, UUID candidateId,
            String contentHash, String indexVersion);

    void insertTrace(RetrievalTrace trace);

    Optional<RetrievalTrace> findTrace(UUID organizationId, UUID traceId);

    /** Lexical candidates among narrowed ids: FTS match + deterministic rank order. */
    List<CandidateHit> findLexicalCandidates(
            UUID organizationId, List<UUID> candidateIds, String lexicalQuery, int limit);

    /** Vector candidates among narrowed ids, restricted to one embedding model identity. */
    List<CandidateHit> findVectorCandidates(
            UUID organizationId, List<UUID> candidateIds, String embeddingModel,
            int embeddingDims, String queryVectorLiteral, int limit);

    /** One lexical-lane hit with its rank (1-based, deterministic order). */
    record CandidateHit(UUID candidateId, double score, int rank) {
    }



    record EvidenceIndexRow(
            UUID id,
            UUID organizationId,
            UUID candidateId,
            UUID artifactId,
            String candidateType,
            String legalConceptKey,
            String contentHash,
            String indexVersion,
            String lexicalConfig,
            String lexicalText,
            String embeddingModel,
            Integer embeddingDims,
            boolean embeddingIndexReady,
            long publicationNumber,
            Long supersededAtPublication,
            String contractPackKey,
            List<String> declaredContractTypes,
            String partyPerspective,
            String paperSource,
            String jurisdiction,
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {
    }

    /** Idempotent per-model partial HNSW index over the dimensionless vector column. */
    void createVectorIndexIfAbsent(String indexName, String embeddingModel, int dims);
}
