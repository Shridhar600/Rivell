package com.rivell.workbench.retrieval;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.rivell.workbench.intelligence.EmbeddingService;
import com.rivell.workbench.intelligence.SemanticDecisionException;
import com.rivell.workbench.knowledge.CandidateType;
import com.rivell.workbench.knowledge.KnowledgeCandidate;
import com.rivell.workbench.knowledge.KnowledgeQueryService;

/**
 * Evidence index lifecycle (AD #8): published candidates become searchable through
 * the retrieval index. Indexed evidence is derived, rebuildable data; index rows
 * trace back to the stable published candidate identity + content hash and are
 * never the source of authority. Publication is never blocked by indexing failure.
 */
public class EvidenceIndexService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(EvidenceIndexService.class);

    static final Set<CandidateType> INDEXABLE_TYPES =
            Set.of(CandidateType.PRECEDENT, CandidateType.REFERENCE,
                    CandidateType.PLAYBOOK_RULE);

    private final KnowledgeQueryService knowledgeQueries;
    private final EmbeddingService embeddingService;
    private final RetrievalRepository repository;
    private final RetrievalProperties properties;

    public EvidenceIndexService(
            KnowledgeQueryService knowledgeQueries,
            EmbeddingService embeddingService,
            RetrievalRepository repository,
            RetrievalProperties properties) {
        this.knowledgeQueries = knowledgeQueries;
        this.embeddingService = embeddingService;
        this.repository = repository;
        this.properties = properties;
    }

    @org.springframework.transaction.event.TransactionalEventListener(
            phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
    public void onKnowledgePublished(com.rivell.workbench.knowledge.KnowledgePublishedEvent event) {
        if (!properties.indexingEnabled()) {
            return;
        }
        var embeddings = new java.util.LinkedHashMap<UUID, String>();
        for (var candidateId : event.candidateIds()) {
            try {
                var candidate = indexCandidate(
                        event.organizationId(), candidateId, event.publicationNumber());
                if (candidate != null) {
                    embeddings.put(candidateId, candidate.effectiveContentText());
                }
            } catch (RuntimeException e) {
                // one broken candidate must not block the rest of the index batch
                log.warn("Evidence indexing failed for candidate {} in publication {}: {}",
                        candidateId, event.publicationNumber(), e.toString());
            }
        }
        embedBatch(event.organizationId(), embeddings);
    }

    /** One batched provider call per publication; failures degrade to lexical-only. */
    void embedBatch(UUID organizationId, java.util.Map<UUID, String> texts) {
        if (texts.isEmpty() || !embeddingService.isConfigured()) {
            return;
        }
        var pendingIds = new java.util.ArrayList<>(texts.keySet());
        pendingIds.removeAll(repository.findEmbeddingReadyIds(
                organizationId, pendingIds, properties.indexVersion()));
        if (pendingIds.isEmpty()) {
            return;
        }
        var textsToEmbed = new java.util.LinkedHashMap<UUID, String>();
        for (var candidateId : pendingIds) {
            textsToEmbed.put(candidateId, texts.get(candidateId));
        }
        try {
            var execution = embeddingService.embed(List.copyOf(textsToEmbed.values()));
            var candidates = List.copyOf(textsToEmbed.keySet());
            var model = embeddingService.modelIdentity();
            for (int i = 0; i < candidates.size(); i++) {
                var vector = execution.vectors().get(i);
                if (vector.length > com.rivell.workbench.intelligence.provider.EmbeddingProperties
                        .MAX_INDEXABLE_DIMENSIONS) {
                    // fail fast before any persist: rows beyond the pgvector
                    // regular-vector/HNSW capability could never be indexed
                    throw new IllegalStateException(
                            "Provider returned " + vector.length + "-dimension embeddings exceeding "
                                    + "the pgvector HNSW index capability; configure explicit "
                                    + "supported embedding dimensions");
                }
                if (i == 0) {
                    ensureVectorIndex(vector.length);
                }
                try {
                    repository.persistEmbedding(organizationId, candidates.get(i),
                            sha256Hex(textsToEmbed.get(candidates.get(i))),
                            properties.indexVersion(), model, vector.length,
                            VectorLiterals.of(vector));
                } catch (RuntimeException e) {
                    log.warn("Embedding persistence failed for candidate {}: {}",
                            candidates.get(i), e.toString());
                }
            }
        } catch (SemanticDecisionException e) {
            // honest degradation: lexical stays searchable, vector stays not-ready
            log.warn("Embedding generation degraded for organization {}", organizationId);
        }
    }

    KnowledgeCandidate indexCandidate(
            UUID organizationId, UUID candidateId, long publicationNumber) {
        var candidate = knowledgeQueries.getCandidateForOrganization(organizationId, candidateId)
                .orElseThrow(() -> new com.rivell.workbench.knowledge
                        .KnowledgeCandidateNotFoundException(candidateId));
        if (!candidate.isPublished() || !INDEXABLE_TYPES.contains(candidate.candidateType())) {
            return null;
        }
        var text = candidate.effectiveContentText();
        var contentHash = sha256Hex(text);
        var scope = candidate.scope();
        repository.upsertIndexRow(new RetrievalRepository.EvidenceIndexRow(
                UUID.randomUUID(), organizationId, candidateId, candidate.artifactId(),
                candidate.candidateType().name(), candidate.effectiveConceptKey(),
                contentHash, properties.indexVersion(), "english", text,
                null, null, false, publicationNumber,
                scope.contractPackKey(),
                List.copyOf(scope.declaredContractTypes()),
                scope.partyPerspective(),
                scope.paperSource(),
                scope.jurisdiction(),
                scope.effectiveFrom(),
                scope.effectiveTo()));
        return candidate;
    }

    /**
     * Per-model partial HNSW index over the dimensionless embedding column
     * (created on demand, idempotent DDL — the real provider dimension is unknown
     * at migration time, Architecture Decision #8 / research conclusions).
     */
    private void ensureVectorIndex(int dims) {
        var model = embeddingService.modelIdentity();
        var indexName = "idx_retrieval_vector_"
                + sha256Hex(model + ":" + dims).substring(0, 16);
        try {
            repository.createVectorIndexIfAbsent(indexName, model, dims);
        } catch (RuntimeException e) {
            // index creation is an optimization; a failure must not break indexing
            log.warn("Vector index creation skipped for {}", model);
        }
    }

    private String sha256Hex(String text) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            var hex = new StringBuilder(bytes.length * 2);
            for (var value : bytes) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
