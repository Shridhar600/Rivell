package com.rivell.workbench.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.rivell.workbench.intelligence.EmbeddingBatch;
import com.rivell.workbench.intelligence.EmbeddingProvider;
import com.rivell.workbench.intelligence.EmbeddingRequest;
import com.rivell.workbench.intelligence.EmbeddingService;
import com.rivell.workbench.intelligence.provider.EmbeddingProperties;

/**
 * The omissions lane's contract search (spec §4, D5): a playbook position's text ranks the
 * clauses of one contract by shared words and by embedding similarity, fused by rank.
 */
class InMemoryContractClauseSearchTest {

    private static final ContractClauseSearch.Clause GOVERNING_LAW = clause(
            "This Agreement is governed by the laws of the State of Delaware.");
    private static final ContractClauseSearch.Clause DATA = clause(
            "Supplier shall process personal data only under a data processing agreement.");
    private static final ContractClauseSearch.Clause FEES = clause(
            "Fees are payable monthly in arrears against a valid invoice.");
    private static final String DPA_POSITION =
            "A data processing agreement is required before any personal data is shared.";

    private static ContractClauseSearch.Clause clause(String text) {
        return new ContractClauseSearch.Clause(UUID.randomUUID(), text);
    }

    private static EmbeddingService embeddings(EmbeddingProvider provider, boolean configured) {
        return new EmbeddingService(id -> Optional.of(provider),
                new EmbeddingProperties(FakeEmbeddingProvider.PROVIDER_ID, null,
                        configured ? "fake-key" : null, FakeEmbeddingProvider.MODEL,
                        FakeEmbeddingProvider.DIMENSIONS, 0, null, null),
                Clock.systemUTC());
    }

    private static float[] basis(int index) {
        var vector = new float[FakeEmbeddingProvider.DIMENSIONS];
        vector[index] = 1f;
        return vector;
    }

    private static List<UUID> ids(ContractClauseSearch.Result result) {
        return result.hits().getFirst().stream().map(ContractClauseSearch.Hit::headingNodeId).toList();
    }

    @Test
    void theClauseThatSharesTheQuerysWordsRanksFirst() {
        var search = new InMemoryContractClauseSearch(embeddings(new FakeEmbeddingProvider(), false));

        var result = search.search(List.of(DPA_POSITION), List.of(GOVERNING_LAW, DATA, FEES), 3);

        assertThat(ids(result).getFirst()).isEqualTo(DATA.headingNodeId());
        // a clause that shares no word with the position is not a full-text hit
        assertThat(ids(result)).doesNotContain(FEES.headingNodeId());
    }

    @Test
    void theVectorLaneFindsAParaphraseThatSharesNoWord() {
        var provider = new FakeEmbeddingProvider();
        var paraphrase = clause("Customer records stay secret between us.");
        var unrelated = clause("Notices go to the addresses on the cover page.");
        var query = "Personal information must be kept confidential.";
        provider.anchor(paraphrase.text(), basis(4));
        provider.anchor(unrelated.text(), basis(3));
        provider.anchor(query, basis(4));
        var search = new InMemoryContractClauseSearch(embeddings(provider, true));

        var result = search.search(List.of(query), List.of(unrelated, paraphrase), 1);

        var top = result.hits().getFirst().getFirst();
        assertThat(top.headingNodeId()).isEqualTo(paraphrase.headingNodeId());
        assertThat(top.vectorRank()).isEqualTo(1);
        assertThat(top.lexicalRank()).isNull();
        assertThat(result.degradation()).isEmpty();
    }

    @Test
    void noEmbeddingsMeansWordScoreOnly() {
        var search = new InMemoryContractClauseSearch(embeddings(new FakeEmbeddingProvider(), false));

        var result = search.search(List.of(DPA_POSITION), List.of(GOVERNING_LAW, DATA, FEES), 3);

        assertThat(result.degradation()).containsExactly("VECTOR_LANE_UNCONFIGURED");
        assertThat(result.hits().getFirst()).allSatisfy(hit -> assertThat(hit.vectorRank()).isNull());
    }

    @Test
    void anEmbeddingOutageFallsBackToWordsAndSaysSo() {
        EmbeddingProvider down = new EmbeddingProvider() {
            @Override
            public String id() {
                return FakeEmbeddingProvider.PROVIDER_ID;
            }

            @Override
            public EmbeddingBatch embed(EmbeddingRequest request) {
                throw new com.rivell.workbench.intelligence.ProviderUnavailableException("down");
            }
        };
        var search = new InMemoryContractClauseSearch(embeddings(down, true));

        var result = search.search(List.of(DPA_POSITION), List.of(GOVERNING_LAW, DATA, FEES), 3);

        assertThat(result.degradation()).containsExactly("VECTOR_LANE_UNAVAILABLE");
        assertThat(ids(result).getFirst()).isEqualTo(DATA.headingNodeId());
    }

    @Test
    void oneBadInputOnlyLosesItsOwnVectorLaneSlot() {
        var fake = new FakeEmbeddingProvider();
        var bad = clause("bad clause text");
        var good = clause("Customer records stay secret between us.");
        var query = "Personal information must be kept confidential.";
        fake.anchor(good.text(), basis(4));
        fake.anchor(query, basis(4));
        EmbeddingProvider partial = new EmbeddingProvider() {
            @Override
            public String id() {
                return FakeEmbeddingProvider.PROVIDER_ID;
            }

            @Override
            public EmbeddingBatch embed(EmbeddingRequest request) {
                if (request.inputs().size() > 1) {
                    throw new com.rivell.workbench.intelligence.ProviderRejectedException(
                            "batch rejected");
                }
                if (request.inputs().get(0).equals(bad.text())) {
                    throw new com.rivell.workbench.intelligence.ProviderRejectedException(
                            "input rejected");
                }
                return fake.embed(request);
            }
        };
        var search = new InMemoryContractClauseSearch(embeddings(partial, true));

        var result = search.search(List.of(query), List.of(bad, good), 2);

        assertThat(result.degradation()).containsExactly("VECTOR_LANE_PARTIAL");
        var goodHit = result.hits().getFirst().stream()
                .filter(hit -> hit.headingNodeId().equals(good.headingNodeId()))
                .findFirst().orElseThrow();
        assertThat(goodHit.vectorRank()).isEqualTo(1);
        assertThat(result.hits().getFirst()).noneMatch(
                hit -> hit.headingNodeId().equals(bad.headingNodeId()) && hit.vectorRank() != null);
    }

    @Test
    void eachQueryGetsItsOwnRankingFromOneEmbeddingCallPerSide() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var fake = new FakeEmbeddingProvider();
        var feesQuery = "Fees are payable against a valid invoice.";
        // both lanes agree for each query, so the ranking is about the queries, not hash noise
        fake.anchor(DPA_POSITION, basis(5));
        fake.anchor(DATA.text(), basis(5));
        fake.anchor(feesQuery, basis(6));
        fake.anchor(FEES.text(), basis(6));
        EmbeddingProvider counting = new EmbeddingProvider() {
            @Override
            public String id() {
                return FakeEmbeddingProvider.PROVIDER_ID;
            }

            @Override
            public EmbeddingBatch embed(EmbeddingRequest request) {
                calls.incrementAndGet();
                return fake.embed(request);
            }
        };
        var search = new InMemoryContractClauseSearch(embeddings(counting, true));

        var result = search.search(List.of(DPA_POSITION, feesQuery),
                List.of(GOVERNING_LAW, DATA, FEES), 1);

        assertThat(calls).hasValue(2);
        assertThat(result.hits()).hasSize(2);
        assertThat(result.hits().get(0).getFirst().headingNodeId()).isEqualTo(DATA.headingNodeId());
        assertThat(result.hits().get(1).getFirst().headingNodeId()).isEqualTo(FEES.headingNodeId());
    }
}
