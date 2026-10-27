package com.rivell.workbench.intelligence.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import com.rivell.workbench.intelligence.ProviderOutOfCreditException;
import com.rivell.workbench.intelligence.ProviderRateLimitedException;
import com.rivell.workbench.intelligence.ProviderRejectedException;

class OpenAiCompatibleErrorsTest {

    @Test
    void retryAfterParsesSecondsAndHttpDates() {
        var seconds = new HttpHeaders();
        seconds.set(HttpHeaders.RETRY_AFTER, "7");
        assertThat(OpenAiCompatibleErrors.retryAfter(seconds))
                .isEqualTo(Duration.ofSeconds(7));

        var date = new HttpHeaders();
        date.set(HttpHeaders.RETRY_AFTER, DateTimeFormatter.RFC_1123_DATE_TIME
                .format(ZonedDateTime.now(java.time.ZoneOffset.UTC).plusSeconds(12)));
        var parsed = OpenAiCompatibleErrors.retryAfter(date);
        assertThat(parsed).isNotNull();
        assertThat(parsed.toSeconds()).isBetween(10L, 12L);
    }

    @Test
    void onlyTheDocumentedBillingCodesAndQuotaTypeAreOutOfCredit() {
        assertThat(OpenAiCompatibleErrors.outOfCredit(
                "{\"error\":{\"code\":\"credit_balance_exhausted\"}}")).isTrue();
        assertThat(OpenAiCompatibleErrors.outOfCredit(
                "{\"error\":{\"type\":\"insufficient_quota\"}}")).isTrue();
        assertThat(OpenAiCompatibleErrors.outOfCredit(
                "{\"error\":{\"code\":\"rate_limit_exceeded\"}}")).isFalse();
        assertThat(OpenAiCompatibleErrors.outOfCredit("not json")).isFalse();
    }

    @Test
    void anErrorObjectIsClassifiedByCodeAndType() {
        assertThat(OpenAiCompatibleErrors.errorResponse(null, "rate_limit", "generative"))
                .isInstanceOf(ProviderRateLimitedException.class);
        assertThat(OpenAiCompatibleErrors.errorResponse(
                "slow_down", "rate_limit_error", "generative"))
                .isInstanceOf(ProviderRateLimitedException.class);
        assertThat(OpenAiCompatibleErrors.errorResponse(
                "invalid_api_key", "invalid_request_error", "generative"))
                .isExactlyInstanceOf(ProviderRejectedException.class);
        assertThat(OpenAiCompatibleErrors.errorResponse("model_not_found", null, "generative"))
                .isExactlyInstanceOf(ProviderRejectedException.class);
        assertThat(OpenAiCompatibleErrors.errorResponse(
                "context_length_exceeded", null, "generative"))
                .isExactlyInstanceOf(ProviderRejectedException.class);
        assertThat(OpenAiCompatibleErrors.errorResponse(null, "permission_error", "generative"))
                .isExactlyInstanceOf(ProviderRejectedException.class);
        assertThat(OpenAiCompatibleErrors.errorResponse("insufficient_quota", null, "generative"))
                .isInstanceOf(ProviderOutOfCreditException.class);
    }
}
