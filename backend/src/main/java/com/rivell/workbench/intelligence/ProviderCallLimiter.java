package com.rivell.workbench.intelligence;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * One bounded in-flight allowance per provider id. The permit is taken around a whole retried
 * call, not each attempt, so retries never multiply the queue. A caller that cannot get a permit
 * within its remaining time budget fails with {@code provider busy} instead of queueing forever.
 */
public final class ProviderCallLimiter {

    static final int DEFAULT_PERMITS = 8;

    private final Map<String, java.util.concurrent.Semaphore> permits = new java.util.concurrent.ConcurrentHashMap<>();

    public ProviderCallLimiter(Map<String, Integer> configured) {
        var values = new LinkedHashMap<String, Integer>();
        if (configured != null) {
            configured.forEach((provider, count) -> {
                if (provider != null && !provider.isBlank() && count != null) {
                    if (count < 1) {
                        throw new IllegalArgumentException(
                                "rivell.intelligence.max-concurrent." + provider + " must be >= 1");
                    }
                    values.put(provider, count);
                }
            });
        }
        values.putIfAbsent("jev", 16);
        values.putIfAbsent("openai-compatible", 8);
        values.putIfAbsent("openai-decider", 8);
        values.putIfAbsent("embeddings", 8);
        values.forEach((provider, count) -> permits.put(provider, new Semaphore(count)));
    }

    public Permit acquire(String providerId, Duration waitBudget) {
        var semaphore = permits.computeIfAbsent(providerId,
                ignored -> new Semaphore(DEFAULT_PERMITS));
        var waitMillis = Math.max(0L, waitBudget == null ? 0L : waitBudget.toMillis());
        try {
            if (!semaphore.tryAcquire(waitMillis, TimeUnit.MILLISECONDS)) {
                throw new ProviderUnavailableException("provider busy");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderUnavailableException("provider busy");
        }
        return new Permit(semaphore);
    }

    public static final class Permit implements AutoCloseable {

        private final Semaphore semaphore;
        private boolean released;

        private Permit(Semaphore semaphore) {
            this.semaphore = semaphore;
        }

        @Override
        public void close() {
            if (!released) {
                released = true;
                semaphore.release();
            }
        }
    }
}
