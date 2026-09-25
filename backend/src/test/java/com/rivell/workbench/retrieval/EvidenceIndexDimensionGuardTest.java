package com.rivell.workbench.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.rivell.workbench.intelligence.EmbeddingBatch;
import com.rivell.workbench.intelligence.EmbeddingProvider;
import com.rivell.workbench.intelligence.EmbeddingProviderRegistry;
import com.rivell.workbench.intelligence.EmbeddingRequest;
import com.rivell.workbench.intelligence.EmbeddingService;
import com.rivell.workbench.intelligence.TokenUsage;
import com.rivell.workbench.intelligence.provider.EmbeddingProperties;

class EvidenceIndexDimensionGuardTest {

    private static EmbeddingService embeddingServiceReturning(int dims) {
        EmbeddingProvider oversized = new EmbeddingProvider() {
            @Override
            public String id() {
                return "openai-compatible";
            }

            @Override
            public EmbeddingBatch embed(EmbeddingRequest request) {
                return new EmbeddingBatch(
                        List.of(new float[dims], new float[dims]),
                        "oversize-model", new TokenUsage(10, 1));
            }
        };
        var properties = new EmbeddingProperties("openai-compatible",
                "http://localhost", "key", "oversize-model", null, 64, null, null);
        return new EmbeddingService(
                (EmbeddingProviderRegistry) id -> Optional.of(oversized),
                properties, Clock.systemUTC());
    }

    private static RetrievalRepository recordingRepository(
            List<Integer> persistedDims) {
        return new RetrievalRepository() {
            @Override
            public void upsertIndexRow(EvidenceIndexRow row) {
            }

            @Override
            public java.util.Set<UUID> findEmbeddingReadyIds(
                    UUID organizationId, List<UUID> candidateIds, String indexVersion) {
                return java.util.Set.of();
            }

            @Override
            public java.util.Set<UUID> findIndexedIds(
                    UUID organizationId, List<UUID> candidateIds, String indexVersion) {
                return java.util.Set.of();
            }

            @Override
            public void persistEmbedding(UUID organizationId, UUID candidateId,
                    String contentHash, String indexVersion, String embeddingModel,
                    int embeddingDims, String vectorLiteral) {
                persistedDims.add(embeddingDims);
            }

            @Override
            public void insertTrace(RetrievalTrace trace) {
            }

            @Override
            public Optional<RetrievalTrace> findTrace(UUID organizationId, UUID traceId) {
                return Optional.empty();
            }

            @Override
            public List<CandidateHit> findLexicalCandidates(
                    UUID organizationId, List<UUID> candidateIds, String lexicalQuery,
                    int limit, String indexVersion) {
                return List.of();
            }

            @Override
            public List<CandidateHit> findVectorCandidates(
                    UUID organizationId, List<UUID> candidateIds, String embeddingModel,
                    int embeddingDims, String queryVectorLiteral, int limit,
                    String indexVersion) {
                return List.of();
            }

            @Override
            public void createVectorIndexIfAbsent(
                    String indexName, String embeddingModel, int dims) {
            }
        };
    }

    @Test
    void providerResponseBeyondHnswCapabilityFailsBeforeAnyPersist() {
        var persistedDims = new java.util.ArrayList<Integer>();
        var service = new EvidenceIndexService(null,
                embeddingServiceReturning(2001), recordingRepository(persistedDims),
                new RetrievalProperties(true, null, 20, 10, 20, 2, "", 100));

        assertThatThrownBy(() -> service.embedBatch(UUID.randomUUID(),
                Map.of(UUID.randomUUID(), "text one", UUID.randomUUID(), "text two")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2001").hasMessageContaining("HNSW");
        assertThat(persistedDims).isEmpty();
    }

    @Test
    void providerResponseWithinHnswCapabilityPersists() {
        var persistedDims = new java.util.ArrayList<Integer>();
        var service = new EvidenceIndexService(null,
                embeddingServiceReturning(768), recordingRepository(persistedDims),
                new RetrievalProperties(true, null, 20, 10, 20, 2, "", 100));

        service.embedBatch(UUID.randomUUID(),
                Map.of(UUID.randomUUID(), "text one", UUID.randomUUID(), "text two"));

        assertThat(persistedDims).containsExactly(768, 768);
    }
}
