package com.rivell.workbench.intelligence.provider;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.http.HttpHeaders;

final class RetryAfter {

    private RetryAfter() {
    }

    /** Parses {@code Retry-After} as seconds or an HTTP date; null when absent or unreadable. */
    static Duration parse(HttpHeaders headers) {
        if (headers == null) {
            return null;
        }
        var raw = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        var value = raw.trim();
        try {
            return Duration.ofSeconds(Math.max(0L, Long.parseLong(value)));
        } catch (NumberFormatException notSeconds) {
            try {
                var at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant();
                var seconds = Duration.between(Instant.now(), at).toSeconds();
                return Duration.ofSeconds(Math.max(0L, seconds));
            } catch (RuntimeException notDate) {
                return null;
            }
        }
    }
}
