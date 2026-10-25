package com.rivell.workbench.intelligence;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A small per-provider short circuit: after an out-of-credit response, calls to the same
 * provider account fail immediately for a minute instead of paying for a network round trip that
 * cannot succeed. The window is per provider identity, never global.
 */
public final class ProviderCreditGuard {

    public static final Duration DEFAULT_WINDOW = Duration.ofSeconds(60);

    private final Clock clock;
    private final Duration window;
    private final ConcurrentHashMap<String, Instant> blockedUntil = new ConcurrentHashMap<>();

    public ProviderCreditGuard(Clock clock) {
        this(clock, DEFAULT_WINDOW);
    }

    public ProviderCreditGuard(Clock clock, Duration window) {
        this.clock = clock;
        this.window = window == null ? DEFAULT_WINDOW : window;
    }

    public void check(String providerKey) {
        var until = blockedUntil.get(providerKey);
        if (until != null && clock.instant().isBefore(until)) {
            throw new ProviderOutOfCreditException(
                    "Provider account has no credits (short circuit)");
        }
        if (until != null) {
            blockedUntil.remove(providerKey, until);
        }
    }

    public void record(String providerKey) {
        blockedUntil.put(providerKey, clock.instant().plus(window));
    }
}
