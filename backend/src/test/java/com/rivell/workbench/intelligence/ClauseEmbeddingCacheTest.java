package com.rivell.workbench.intelligence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

import com.rivell.workbench.intelligence.provider.EmbeddingProperties;

/** B1: one review embeds each distinct clause text once and reuses the same vectors. */
class ClauseEmbeddingCacheTest {

    private static final class CountingProvider implements EmbeddingProvider {

        private final List<List<String>> requests = new CopyOnWriteArrayList<>();

        @Override
        public String id() {
            return "fake";
        }

        @Override
        public EmbeddingBatch embed(EmbeddingRequest request) {
            requests.add(request.inputs());
            var vectors = new ArrayList<float[]>();
            for (var input : request.inputs()) {
                vectors.add(new float[] { input.hashCode(), input.length() });
            }
            return new EmbeddingBatch(vectors, "embed-model-v1",
                    new TokenUsage(request.inputs().size(), 0));
        }
    }

    @Test
    void aThreeClauseReviewMakesOneRequestAndReusesTheSameVectors() {
        var provider = new CountingProvider();
        var properties = new EmbeddingProperties("fake", "https://embed.test/v1", "key",
                "embed-model", null, 64, Duration.ofSeconds(1), Duration.ofSeconds(1));
        EmbeddingProviderRegistry registry = id -> id.equals("fake")
                ? Optional.of(provider) : Optional.empty();
        var service = new EmbeddingService(registry, properties, Clock.systemUTC());

        var cache = new ClauseEmbeddingCache(service);
        cache.prefetch(List.of("clause one", "clause two", "clause three"));

        assertThat(provider.requests).hasSize(1);
        assertThat(provider.requests.get(0))
                .containsExactly("clause one", "clause two", "clause three");

        var cached = cache.vector("clause two");
        assertThat(provider.requests).hasSize(1);

        var direct = service.embed(List.of("clause two")).vectors().get(0);
        assertThat(cached).containsExactly(direct);
    }

    @Test
    void aFailedPrefetchFallsBackPerTextSoOnlyTheBadInputIsLost() {
        var provider = new EmbeddingProvider() {
            @Override
            public String id() {
                return "fake";
            }

            @Override
            public EmbeddingBatch embed(EmbeddingRequest request) {
                if (request.inputs().size() > 1) {
                    throw new ProviderRejectedException("batch too large");
                }
                if (request.inputs().get(0).equals("bad")) {
                    throw new ProviderRejectedException("input too large");
                }
                return new EmbeddingBatch(
                        List.of(new float[] { request.inputs().get(0).length() }),
                        "embed-model-v1", new TokenUsage(1, 0));
            }
        };
        var properties = new EmbeddingProperties("fake", "https://embed.test/v1", "key",
                "embed-model", null, 64, Duration.ofSeconds(1), Duration.ofSeconds(1));
        EmbeddingProviderRegistry registry = id -> id.equals("fake")
                ? Optional.of(provider) : Optional.empty();
        var cache = new ClauseEmbeddingCache(
                new EmbeddingService(registry, properties, Clock.systemUTC()));

        cache.prefetch(List.of("good", "bad", "other"));

        assertThat(cache.vector("good")).containsExactly(4f);
        assertThat(cache.vector("other")).containsExactly(5f);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> cache.vector("bad"))
                .isInstanceOf(ProviderRejectedException.class);
    }

    @Test
    void aFirstUseWithoutPrefetchEmbedsOnlyThatText() {
        var provider = new CountingProvider();
        var properties = new EmbeddingProperties("fake", "https://embed.test/v1", "key",
                "embed-model", null, 64, Duration.ofSeconds(1), Duration.ofSeconds(1));
        EmbeddingProviderRegistry registry = id -> id.equals("fake")
                ? Optional.of(provider) : Optional.empty();
        var cache = new ClauseEmbeddingCache(
                new EmbeddingService(registry, properties, Clock.systemUTC()));

        cache.vector("only");

        assertThat(provider.requests).hasSize(1);
        assertThat(provider.requests.get(0)).containsExactly("only");
    }
}
