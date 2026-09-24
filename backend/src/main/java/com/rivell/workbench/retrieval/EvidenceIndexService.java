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

    private static final Set<CandidateType> INDEXABLE_TYPES =
            Set.of(CandidateType.PRECEDENT, CandidateType.REFERENCE);

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

    @org.springframework.modulith.ApplicationModuleListener
    public void onKnowledgePublished(com.rivell.workbench.knowledge.KnowledgePublishedEvent event) {
        if (!properties.indexingEnabled()) {
            return;
        }
        for (var superseded : event.supersededCandidates().entrySet()) {
            repository.markSuperseded(
                    event.organizationId(), superseded.getValue(), event.publicationNumber());
        }
        for (var candidateId : event.candidateIds()) {
            indexCandidate(event.organizationId(), candidateId, event.publicationNumber());
        }
    }

    private void indexCandidate(UUID organizationId, UUID candidateId, long publicationNumber) {
        var candidate = knowledgeQueries.getCandidate(candidateId);
        if (!candidate.isPublished() || !INDEXABLE_TYPES.contains(candidate.candidateType())) {
            return;
        }
        var text = candidate.effectiveContentText();
        var contentHash = sha256Hex(text);
        var scope = candidate.scope();
        repository.upsertIndexRow(new RetrievalRepository.EvidenceIndexRow(
                UUID.randomUUID(), organizationId, candidateId, candidate.artifactId(),
                candidate.candidateType().name(), candidate.legalConceptKey(),
                contentHash, properties.indexVersion(), "english", text,
                null, null, false, publicationNumber, null,
                scope.contractPackKey(),
                List.copyOf(scope.declaredContractTypes()),
                scope.partyPerspective(),
                scope.paperSource(),
                scope.jurisdiction(),
                scope.effectiveFrom(),
                scope.effectiveTo()));
        embed(organizationId, candidateId, contentHash, text);
    }

    private void embed(UUID organizationId, UUID candidateId, String contentHash, String text) {
        if (!embeddingService.isConfigured()) {
            return;
        }
        try {
            var execution = embeddingService.embed(List.of(text));
            var vector = execution.vectors().get(0);
            repository.persistEmbedding(candidateId, contentHash, properties.indexVersion(),
                    embeddingService.modelIdentity(), vector.length,
                    VectorLiterals.of(vector));
        } catch (SemanticDecisionException e) {
            // honest degradation: lexical stays searchable, vector stays not-ready
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
