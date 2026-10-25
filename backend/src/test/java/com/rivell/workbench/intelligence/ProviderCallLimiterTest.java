package com.rivell.workbench.intelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ProviderCallLimiterTest {

    @Test
    void theNinthConcurrentCallerWaitsThenFailsWhenItsBudgetIsSpent() throws Exception {
        var limiter = new ProviderCallLimiter(Map.of("openai-compatible", 8));
        var held = new java.util.ArrayList<ProviderCallLimiter.Permit>();
        for (int index = 0; index < 8; index++) {
            held.add(limiter.acquire("openai-compatible", Duration.ZERO));
        }

        assertThatThrownBy(() -> limiter.acquire("openai-compatible", Duration.ofMillis(100)))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessage("provider busy");

        for (var permit : held) {
            permit.close();
        }
        try (var ignored = limiter.acquire("openai-compatible", Duration.ZERO)) {
            assertThat(ignored).isNotNull();
        }
    }

    @Test
    void permitsArePerProvider() {
        var limiter = new ProviderCallLimiter(Map.of("jev", 1, "openai-compatible", 1));
        try (var jev = limiter.acquire("jev", Duration.ZERO)) {
            try (var openAi = limiter.acquire("openai-compatible", Duration.ZERO)) {
                assertThat(openAi).isNotNull();
            }
        }
    }

    @Test
    void anUnknownProviderUsesTheDefaultAllowance() {
        var limiter = new ProviderCallLimiter(Map.of());
        var held = new java.util.ArrayList<ProviderCallLimiter.Permit>();
        for (int index = 0; index < ProviderCallLimiter.DEFAULT_PERMITS; index++) {
            held.add(limiter.acquire("other", Duration.ZERO));
        }

        assertThatThrownBy(() -> limiter.acquire("other", Duration.ofMillis(100)))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessage("provider busy");

        for (var permit : held) {
            permit.close();
        }
        try (var ignored = limiter.acquire("other", Duration.ZERO)) {
            assertThat(ignored).isNotNull();
        }
    }
}
