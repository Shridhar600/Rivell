package com.rivell.workbench.intelligence.provider;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;

import org.springframework.http.HttpHeaders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rivell.workbench.intelligence.ProviderOutOfCreditException;
import com.rivell.workbench.intelligence.ProviderProtocolException;
import com.rivell.workbench.intelligence.ProviderRateLimitedException;
import com.rivell.workbench.intelligence.ProviderRejectedException;
import com.rivell.workbench.intelligence.ProviderUnavailableException;

/**
 * Classification shared by every OpenAI-compatible adapter. Reads only the documented error
 * {@code code} and {@code type} values and the {@code Retry-After} header; the response body is
 * never logged or put into an exception message.
 */
final class OpenAiCompatibleErrors {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> OUT_OF_CREDIT_CODES = Set.of(
            "credit_balance_exhausted",
            "organization_spend_limit_exceeded",
            "project_spend_limit_exceeded",
            "organization_usage_limit_exceeded",
            "insufficient_quota");

    private static final Set<String> RATE_LIMIT_CODES = Set.of(
            "rate_limit",
            "rate_limit_exceeded",
            "rate_limit_error",
            "slow_down");

    private OpenAiCompatibleErrors() {
    }

    /**
     * Maps one non-2xx response to the controlled vocabulary. {@code kind} is the adapter's noun
     * ("generative", "decision", "embedding") and is the only context added to the message.
     */
    static RuntimeException httpFailure(int status, String body, HttpHeaders headers,
            String kind) {
        if (outOfCredit(body)) {
            return new ProviderOutOfCreditException(
                    "Provider account has no credits (HTTP " + status + ")");
        }
        if (status == 429) {
            return new ProviderRateLimitedException(
                    "Provider rate limited (HTTP 429)", retryAfter(headers), 429);
        }
        var retryAfter = retryAfter(headers);
        if (status == 503 && retryAfter != null) {
            return new ProviderRateLimitedException(
                    "Provider unavailable (HTTP 503)", retryAfter, 503);
        }
        if (status >= 400 && status < 500) {
            return new ProviderRejectedException(
                    "Provider rejected the " + kind + " request (HTTP " + status + ")", status);
        }
        if (status >= 500) {
            return new ProviderUnavailableException(
                    "Provider unavailable (HTTP " + status + ")", status);
        }
        return new ProviderProtocolException(
                "Provider returned unexpected HTTP status " + status);
    }

    /** True when the body names one of the documented billing or spend-limit errors. */
    static boolean outOfCredit(String body) {
        var error = error(body);
        return outOfCredit(error.code, error.type);
    }

    static boolean outOfCredit(String code, String type) {
        return OUT_OF_CREDIT_CODES.contains(normalize(code))
                || OUT_OF_CREDIT_CODES.contains(normalize(type));
    }

    /**
     * Maps one error object carried inside a 2xx response to the same vocabulary as
     * {@link #httpFailure}, so a permanent rejection is not retried as if it were an outage.
     */
    static RuntimeException errorResponse(String code, String type, String kind) {
        if (outOfCredit(code, type)) {
            return new ProviderOutOfCreditException(
                    "Provider account has no credits (error response)");
        }
        var detail = "type=" + type + ", code=" + code;
        if (rateLimited(code, type)) {
            return new ProviderRateLimitedException(
                    "Provider rate limited (error response: " + detail + ")", null, null);
        }
        return new ProviderRejectedException(
                "Provider rejected the " + kind + " request (error response: " + detail + ")");
    }

    private static boolean rateLimited(String code, String type) {
        return RATE_LIMIT_CODES.contains(normalize(code))
                || RATE_LIMIT_CODES.contains(normalize(type));
    }

    static Error error(String body) {
        if (body == null || body.isBlank()) {
            return Error.NONE;
        }
        try {
            var node = MAPPER.readTree(body);
            var error = node.path("error");
            return new Error(text(error, "code"), text(error, "type"));
        } catch (Exception e) {
            return Error.NONE;
        }
    }

    /** Parses {@code Retry-After} as seconds or an HTTP date; null when absent or unreadable. */
    static Duration retryAfter(HttpHeaders headers) {
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

    private static String text(JsonNode node, String field) {
        var value = node.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    record Error(String code, String type) {

        static final Error NONE = new Error(null, null);
    }
}
