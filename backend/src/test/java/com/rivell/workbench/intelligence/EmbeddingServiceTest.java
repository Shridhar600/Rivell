package com.rivell.workbench.intelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.rivell.workbench.intelligence.provider.EmbeddingProperties;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class EmbeddingServiceTest {

    private static EmbeddingProperties properties() {
        return new EmbeddingProperties("fake", "https://embed.test/v1", "key", "embed-model",
                null, 64, Duration.ofSeconds(1), Duration.ofSeconds(1));
    }

    private static EmbeddingService service(EmbeddingProvider provider, ProviderRetry retry) {
        EmbeddingProviderRegistry registry = id -> id.equals("fake")
                ? Optional.of(provider) : Optional.empty();
        return new EmbeddingService(registry, properties(), Clock.systemUTC(), retry);
    }

    private static ProviderRetry retry() {
        return new ProviderRetry(List.of(Duration.ZERO), Duration.ofSeconds(90),
                Clock.systemUTC(), duration -> { }, new Random(1));
    }

    private static EmbeddingBatch batch(List<String> inputs) {
        var vectors = new ArrayList<float[]>();
        for (int index = 0; index < inputs.size(); index++) {
            vectors.add(new float[] { index, index + 0.5f });
        }
        return new EmbeddingBatch(vectors, "embed-model-v1", new TokenUsage(inputs.size(), 0));
    }

    @Test
    void retriesA503AndThenSucceeds() {
        var calls = new AtomicInteger();
        var provider = new EmbeddingProvider() {
            @Override
            public String id() {
                return "fake";
            }

            @Override
            public EmbeddingBatch embed(EmbeddingRequest request) {
                if (calls.incrementAndGet() == 1) {
                    throw new ProviderUnavailableException("Embedding provider unavailable (HTTP 503)");
                }
                return batch(request.inputs());
            }
        };

        var execution = service(provider, retry()).embed(List.of("a", "b"));

        assertThat(calls).hasValue(2);
        assertThat(execution.vectors()).hasSize(2);
    }

    @Test
    void aFailureIsLoggedAsAModelExecutionFailure() {
        var logger = (Logger) LoggerFactory.getLogger("rivell.model-execution");
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var provider = new EmbeddingProvider() {
                @Override
                public String id() {
                    return "fake";
                }

                @Override
                public EmbeddingBatch embed(EmbeddingRequest request) {
                    throw new ProviderRejectedException("Embedding provider rejected the request");
                }
            };

            assertThatThrownBy(() -> service(provider, ProviderRetry.NONE).embed(List.of("a")))
                    .isInstanceOf(ProviderRejectedException.class);

            assertThat(appender.list)
                    .anySatisfy(event -> {
                        var fields = event.getKeyValuePairs().stream()
                                .collect(java.util.stream.Collectors.toMap(
                                        pair -> pair.key, pair -> String.valueOf(pair.value)));
                        assertThat(fields).containsEntry("kind", "embedding");
                        assertThat(fields).containsEntry("outcome", "FAILED");
                    });
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void aCanceledCallIsNotRetried() {
        var calls = new AtomicInteger();
        var provider = new EmbeddingProvider() {
            @Override
            public String id() {
                return "fake";
            }

            @Override
            public EmbeddingBatch embed(EmbeddingRequest request) {
                calls.incrementAndGet();
                throw new ProviderRejectedException("Embedding provider rejected the request");
            }
        };

        assertThatThrownBy(() -> service(provider, retry()).embed(List.of("a")))
                .isInstanceOf(ProviderRejectedException.class);
        assertThat(calls).hasValue(1);
    }

    private static final class MutableClock extends Clock {

        private Instant instant = Instant.parse("2026-10-24T10:00:00Z");

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
