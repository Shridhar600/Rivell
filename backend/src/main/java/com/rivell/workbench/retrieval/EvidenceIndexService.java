package com.rivell.workbench.retrieval;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.rivell.workbench.intelligence.EmbeddingService;
import com.rivell.workbench.intelligence.ProviderFailures;
import com.rivell.workbench.intelligence.ProviderRejectedException;
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

    /** Whether the vector lane can run at all. Without a provider there is no vector gap to heal. */
    boolean embeddingsConfigured() {
        return embeddingService.isConfigured();
    }

    /**
     * One batched provider call per publication; failures degrade to lexical-only. Returns how
     * many rows were actually persisted, so a caller's counters cannot claim vectors that were
     * never written.
     */
    int embedBatch(UUID organizationId, java.util.Map<UUID, String> texts) {
        if (texts.isEmpty()) {
            retryPendingVectorIndexes();
            return 0;
        }
        if (!embeddingService.isConfigured()) {
            return 0;
        }
        var pendingIds = new java.util.ArrayList<>(texts.keySet());
        pendingIds.removeAll(repository.findEmbeddingReadyIds(
                organizationId, pendingIds, properties.indexVersion()));
        // Candidate ids are random, so sorting the batch by content makes the same
        // knowledge request the same inputs in the same order on every run.
        pendingIds.sort(java.util.Comparator.comparing(
                (UUID candidateId) -> java.util.Objects.toString(texts.get(candidateId), "")));
        // Retry after the readiness read: publication calls this inside an AFTER_COMMIT listener,
        // where a failed CREATE INDEX can abort the connection's implicit transaction. The read
        // must happen first; the persistence failures that follow are contained per candidate.
        retryPendingVectorIndexes();
        if (pendingIds.isEmpty()) {
            return 0;
        }
        var persisted = 0;
        var textsToEmbed = new java.util.LinkedHashMap<UUID, String>();
        for (var candidateId : pendingIds) {
            textsToEmbed.put(candidateId, texts.get(candidateId));
        }
        try {
            var execution = embeddingService.embed(List.copyOf(textsToEmbed.values()));
            var candidates = List.copyOf(textsToEmbed.keySet());
            var model = embeddingService.modelIdentity();
            for (int i = 0; i < candidates.size(); i++) {
                if (persistVector(organizationId, candidates.get(i),
                        textsToEmbed.get(candidates.get(i)), model, execution.vectors().get(i))) {
                    persisted++;
                }
            }
        } catch (SemanticDecisionException e) {
            if (ProviderFailures.isOutOfCredit(e)) {
                log.warn("Embedding generation degraded for organization {} (no credit)",
                        organizationId);
                return persisted;
            }
            if (!(e instanceof ProviderRejectedException)) {
                // a timeout or outage is not about one input: retrying each text would spend the
                // same failing endpoint's budget once per input, so this pass stays lexical-only
                log.warn("Embedding generation deferred for organization {}: {}",
                        organizationId, e.toString());
                return persisted;
            }
            // the batch was rejected: one bad input must not leave the entire batch unindexed
            var model = embeddingService.modelIdentity();
            for (var entry : textsToEmbed.entrySet()) {
                try {
                    var vector = embeddingService.embed(List.of(entry.getValue()))
                            .vectors().get(0);
                    if (persistVector(organizationId, entry.getKey(), entry.getValue(),
                            model, vector)) {
                        persisted++;
                    }
                } catch (SemanticDecisionException ignored) {
                    if (ProviderFailures.isOutOfCredit(ignored)) {
                        log.warn("Embedding generation degraded for organization {} (no credit)",
                                organizationId);
                        break;
                    }
                    // this input stays unindexed; the other inputs were still persisted
                }
            }
        }
        return persisted;
    }

    private final java.util.Set<Integer> ensuredDimensions =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<Integer> pendingDimensions =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Object vectorIndexLock = new Object();

    /**
     * Retries dimensions whose last index creation failed. Called at the start of every embedding
     * pass, including one where every row already has a vector, so a failed {@code CREATE INDEX}
     * cannot silence the vector lane until the JVM restarts.
     */
    int retryPendingVectorIndexes() {
        if (!embeddingService.isConfigured()) {
            return 0;
        }
        synchronized (vectorIndexLock) {
            var retried = 0;
            for (var dims : List.copyOf(pendingDimensions)) {
                if (ensureVectorIndex(dims)) {
                    pendingDimensions.remove(dims);
                    ensuredDimensions.add(dims);
                    retried++;
                }
            }
            return retried;
        }
    }

    /** Creates the index once per dimension, and never more than once per pass after a failure. */
    private void ensureVectorIndexOnce(int dims) {
        synchronized (vectorIndexLock) {
            if (ensuredDimensions.contains(dims) || pendingDimensions.contains(dims)) {
                return;
            }
            if (ensureVectorIndex(dims)) {
                ensuredDimensions.add(dims);
            } else {
                pendingDimensions.add(dims);
            }
        }
    }

    private boolean persistVector(UUID organizationId, UUID candidateId, String text,
            String model, float[] vector) {
        if (vector.length > com.rivell.workbench.intelligence.provider.EmbeddingProperties
                .MAX_INDEXABLE_DIMENSIONS) {
            // fail fast before any persist: rows beyond the pgvector
            // regular-vector/HNSW capability could never be indexed
            throw new IllegalStateException(
                    "Provider returned " + vector.length + "-dimension embeddings exceeding "
                            + "the pgvector HNSW index capability; configure explicit "
                            + "supported embedding dimensions");
        }
        ensureVectorIndexOnce(vector.length);
        try {
            repository.persistEmbedding(organizationId, candidateId,
                    sha256Hex(text), properties.indexVersion(), model, vector.length,
                    VectorLiterals.of(vector));
            return true;
        } catch (RuntimeException e) {
            log.warn("Embedding persistence failed for candidate {}: {}",
                    candidateId, e.toString());
            return false;
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
    private boolean ensureVectorIndex(int dims) {
        var model = embeddingService.modelIdentity();
        var indexName = "idx_retrieval_vector_"
                + sha256Hex(model + ":" + dims).substring(0, 16);
        try {
            repository.createVectorIndexIfAbsent(indexName, model, dims);
            return true;
        } catch (RuntimeException e) {
            // index creation is an optimization; a failure must not break indexing, and the
            // dimension is not remembered so the next pass retries it
            log.warn("Vector index creation deferred for {}; will retry on the next pass", model);
            return false;
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
