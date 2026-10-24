package com.rivell.workbench.intelligence.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

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
}
