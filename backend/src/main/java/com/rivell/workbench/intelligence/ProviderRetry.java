package com.rivell.workbench.intelligence;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Bounded retry for a provider that is briefly unavailable (HTTP 429, 5xx, unreachable) or slow.
 * A Jev outage of a few minutes (dev2 eval, 2026-10-12) left 36 clauses of one contract with no
 * topic, so they were never reviewed. Any other failure is not retried: a rejected request or a
 * malformed answer would fail the same way again.
 */
public final class ProviderRetry {

    public static final ProviderRetry NONE = new ProviderRetry(List.of());

    private final List<Duration> backoff;

    public ProviderRetry(List<Duration> backoff) {
        this.backoff = backoff == null ? List.of() : List.copyOf(backoff);
    }

    public List<Duration> backoff() {
        return backoff;
    }

    <T> T call(Supplier<T> attempt, Consumer<RuntimeException> onRetry) {
        for (int retry = 0; ; retry++) {
            try {
                return attempt.get();
            } catch (ProviderUnavailableException | ProviderTimeoutException e) {
                if (retry >= backoff.size()) {
                    throw e;
                }
                onRetry.accept(e);
                try {
                    Thread.sleep(backoff.get(retry));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }
}
