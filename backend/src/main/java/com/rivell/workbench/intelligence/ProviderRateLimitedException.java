package com.rivell.workbench.intelligence;

import java.time.Duration;

/**
 * The provider asked the caller to slow down (HTTP 429, or 503 with a Retry-After). Retryable,
 * and carries the provider's own instruction when it sent one.
 */
public class ProviderRateLimitedException extends ProviderUnavailableException {

    private final Duration retryAfter;

    public ProviderRateLimitedException(String message, Duration retryAfter, Integer httpStatus) {
        super(message, httpStatus);
        this.retryAfter = retryAfter == null || retryAfter.isNegative() ? Duration.ZERO : retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
