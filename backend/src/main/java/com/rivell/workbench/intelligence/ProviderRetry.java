package com.rivell.workbench.intelligence;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Bounded retry for a provider that is briefly unavailable (HTTP 429, 5xx, unreachable) or slow.
 * A Jev outage of a few minutes (dev2 eval, 2026-10-12) left 36 clauses of one contract with no
 * topic, so they were never reviewed. Any other failure is not retried: a rejected request or a
 * malformed answer would fail the same way again.
 *
 * <p>Waits are jittered and follow a provider's {@code Retry-After} when it sent one. One call
 * has an absolute elapsed budget, a timeout is retried at most once, and the wait for a provider
 * permit counts against the same budget.
 */
public final class ProviderRetry {

    public static final Duration DEFAULT_MAX_ELAPSED = Duration.ofSeconds(90);

    /** One failed attempt that will be repeated; the structured facts of the wait. */
    public record RetryEvent(int attempt, Duration backoff, Duration retryAfter,
            RuntimeException failure) {
    }

    @FunctionalInterface
    public interface Sleeper {

        void sleep(Duration duration) throws InterruptedException;
    }

    public static final ProviderRetry NONE = new ProviderRetry(List.of(), Duration.ZERO,
            Clock.systemUTC(), duration -> { }, new Random(), null);

    private final List<Duration> backoff;
    private final Duration maxElapsed;
    private final Clock clock;
    private final Sleeper sleeper;
    private final Random random;
    private final ProviderCallLimiter limiter;

    public ProviderRetry(List<Duration> backoff) {
        this(backoff, DEFAULT_MAX_ELAPSED, Clock.systemUTC(), Thread::sleep,
                new Random(), null);
    }

    public ProviderRetry(List<Duration> backoff, Duration maxElapsed, Clock clock,
            Sleeper sleeper, Random random) {
        this(backoff, maxElapsed, clock, sleeper, random, null);
    }

    public ProviderRetry(List<Duration> backoff, Duration maxElapsed, Clock clock,
            Sleeper sleeper, Random random, ProviderCallLimiter limiter) {
        this.backoff = backoff == null ? List.of() : List.copyOf(backoff);
        this.maxElapsed = maxElapsed == null ? DEFAULT_MAX_ELAPSED : maxElapsed;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.sleeper = sleeper == null ? Thread::sleep : sleeper;
        this.random = random == null ? new Random() : random;
        this.limiter = limiter;
    }

    public List<Duration> backoff() {
        return backoff;
    }

    public <T> T call(String providerId, Supplier<T> attempt, Consumer<RetryEvent> onRetry) {
        var deadline = clock.instant().plus(maxElapsed);
        var permit = limiter == null ? null : limiter.acquire(providerId,
                remaining(deadline));
        try {
            if (permit != null && remaining(deadline).isZero()) {
                throw new ProviderUnavailableException("provider busy");
            }
            return attempts(attempt, onRetry, deadline);
        } finally {
            if (permit != null) {
                permit.close();
            }
        }
    }

    private <T> T attempts(Supplier<T> attempt, Consumer<RetryEvent> onRetry,
            java.time.Instant deadline) {
        var timeoutRetried = false;
        for (int retry = 0; ; retry++) {
            try {
                return attempt.get();
            } catch (ProviderTimeoutException e) {
                if (timeoutRetried) {
                    throw e;
                }
                timeoutRetried = true;
                retryWait(retry, e, Duration.ZERO, onRetry, deadline);
            } catch (ProviderUnavailableException e) {
                var retryAfter = e instanceof ProviderRateLimitedException rateLimited
                        ? rateLimited.retryAfter() : Duration.ZERO;
                retryWait(retry, e, retryAfter, onRetry, deadline);
            }
        }
    }

    private void retryWait(int retry, RuntimeException failure, Duration retryAfter,
            Consumer<RetryEvent> onRetry, java.time.Instant deadline) {
        if (retry >= backoff.size()) {
            throw failure;
        }
        var scheduled = backoff.get(retry);
        var jittered = jitter(scheduled);
        var wait = jittered.compareTo(retryAfter) >= 0 ? jittered : retryAfter;
        var remaining = remaining(deadline);
        if (remaining.isZero() || remaining.isNegative()
                || wait.compareTo(remaining) > 0) {
            throw failure;
        }
        onRetry.accept(new RetryEvent(retry + 1, jittered, retryAfter, failure));
        try {
            sleeper.sleep(wait);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw failure;
        }
    }

    private Duration jitter(Duration scheduled) {
        if (scheduled.isZero() || scheduled.isNegative()) {
            return Duration.ZERO;
        }
        // equal jitter: at least half the scheduled wait, so an outage still gets the recovery
        // time the schedule was tuned for, while concurrent callers spread over the other half
        var half = scheduled.toNanos() / 2;
        return Duration.ofNanos(half + (long) (half * random.nextDouble()));
    }

    private Duration remaining(java.time.Instant deadline) {
        var remaining = Duration.between(clock.instant(), deadline);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }
}
