package com.rivell.workbench.intelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class ProviderRetryTest {

    private static final Instant START = Instant.parse("2026-10-24T10:00:00Z");

    private final MutableClock clock = new MutableClock(START);
    private final List<Duration> sleeps = new ArrayList<>();

    private ProviderRetry retry(List<Duration> backoff, Duration maxElapsed, long seed) {
        return new ProviderRetry(backoff, maxElapsed, clock, duration -> {
            sleeps.add(duration);
            clock.advance(duration);
        }, new Random(seed));
    }

    @Test
    void stopsAtTheElapsedCap() {
        var calls = new AtomicInteger();
        var backoff = java.util.Collections.nCopies(20, Duration.ofSeconds(5));
        var retry = retry(backoff, Duration.ofSeconds(10), 7);

        assertThatThrownBy(() -> retry.call("fake", () -> {
            calls.incrementAndGet();
            throw new ProviderUnavailableException("still down");
        }, event -> { })).isInstanceOf(ProviderUnavailableException.class);

        assertThat(calls.get()).isGreaterThan(1);
        assertThat(sleeps.stream().reduce(Duration.ZERO, Duration::plus))
                .isLessThanOrEqualTo(Duration.ofSeconds(10));
        assertThat(Duration.between(START, clock.instant()))
                .isLessThanOrEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void honoursRetryAfterLargerThanTheBackoff() {
        var calls = new AtomicInteger();
        var retry = retry(List.of(Duration.ofSeconds(1)), Duration.ofSeconds(90), 3);

        var result = retry.call("fake", () -> {
            if (calls.incrementAndGet() == 1) {
                throw new ProviderRateLimitedException("slow down", Duration.ofSeconds(7), 429);
            }
            return "ok";
        }, event -> { });

        assertThat(result).isEqualTo("ok");
        assertThat(sleeps).containsExactly(Duration.ofSeconds(7));
    }

    @Test
    void jitterStaysWithinTheScheduledBackoff() {
        var calls = new AtomicInteger();
        var retry = retry(List.of(Duration.ofSeconds(8)), Duration.ofSeconds(90), 11);

        retry.call("fake", () -> {
            if (calls.incrementAndGet() == 1) {
                throw new ProviderUnavailableException("down");
            }
            return "ok";
        }, event -> { });

        assertThat(sleeps).hasSize(1);
        assertThat(sleeps.get(0)).isBetween(Duration.ofSeconds(4), Duration.ofSeconds(8));
    }

    @Test
    void jitterNeverCutsTheWaitBelowHalfTheSchedule() {
        // a provider outage of a few minutes needs the waits it was tuned for; jitter only spreads them
        var calls = new AtomicInteger();
        var lowest = new Random() {
            @Override
            public double nextDouble() {
                return 0.0;
            }
        };
        var retry = new ProviderRetry(List.of(Duration.ofSeconds(8)), Duration.ofSeconds(90), clock,
                duration -> {
                    sleeps.add(duration);
                    clock.advance(duration);
                }, lowest);

        retry.call("fake", () -> {
            if (calls.incrementAndGet() == 1) {
                throw new ProviderUnavailableException("down");
            }
            return "ok";
        }, event -> { });

        assertThat(sleeps).containsExactly(Duration.ofSeconds(4));
    }

    @Test
    void aTimeoutIsRetriedOnceOnly() {
        var calls = new AtomicInteger();
        var retry = retry(List.of(Duration.ofSeconds(1), Duration.ofSeconds(2)),
                Duration.ofSeconds(90), 5);

        assertThatThrownBy(() -> retry.call("fake", () -> {
            calls.incrementAndGet();
            throw new ProviderTimeoutException("too slow");
        }, event -> { })).isInstanceOf(ProviderTimeoutException.class);

        assertThat(calls).hasValue(2);
        assertThat(sleeps).hasSize(1);
    }

    @Test
    void aRetryAfterBeyondTheRemainingBudgetIsNotSlept() {
        var calls = new AtomicInteger();
        var retry = retry(List.of(Duration.ofSeconds(1)), Duration.ofSeconds(10), 3);

        assertThatThrownBy(() -> retry.call("fake", () -> {
            calls.incrementAndGet();
            throw new ProviderRateLimitedException("slow down", Duration.ofSeconds(30), 429);
        }, event -> { })).isInstanceOf(ProviderRateLimitedException.class);

        assertThat(calls).hasValue(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void noAttemptRunsWhenThePermitWaitSpentTheBudget() {
        var calls = new AtomicInteger();
        var steppingClock = new SteppingClock(START, 100);
        var limiter = new ProviderCallLimiter(Map.of("fake", 1));
        var retry = new ProviderRetry(List.of(Duration.ofSeconds(1)), Duration.ofSeconds(10),
                steppingClock, duration -> { }, new Random(1), limiter);

        assertThatThrownBy(() -> retry.call("fake", () -> {
            calls.incrementAndGet();
            return "ok";
        }, event -> { })).isInstanceOf(ProviderUnavailableException.class)
                .hasMessage("provider busy");

        assertThat(calls).hasValue(0);
    }

    @Test
    void outOfCreditIsNotRetried() {
        var calls = new AtomicInteger();
        var retry = retry(List.of(Duration.ofSeconds(1)), Duration.ofSeconds(90), 5);

        assertThatThrownBy(() -> retry.call("fake", () -> {
            calls.incrementAndGet();
            throw new ProviderOutOfCreditException("no credits");
        }, event -> { })).isInstanceOf(ProviderOutOfCreditException.class);

        assertThat(calls).hasValue(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void thePermitIsReleasedWhenTheCallFails() {
        var limiter = new ProviderCallLimiter(Map.of("fake", 1));
        var retry = new ProviderRetry(List.of(), Duration.ofSeconds(1), clock,
                duration -> { }, new Random(1), limiter);

        assertThatThrownBy(() -> retry.call("fake", () -> {
            throw new ProviderRejectedException("no");
        }, event -> { })).isInstanceOf(ProviderRejectedException.class);

        // a leaked permit would time out here
        try (var ignored = limiter.acquire("fake", Duration.ZERO)) {
            assertThat(ignored).isNotNull();
        }
    }

    private static final class SteppingClock extends Clock {

        private final Instant start;
        private final long stepSeconds;
        private int calls;

        private SteppingClock(Instant start, long stepSeconds) {
            this.start = start;
            this.stepSeconds = stepSeconds;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return start.plusSeconds(stepSeconds * calls++);
        }
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
