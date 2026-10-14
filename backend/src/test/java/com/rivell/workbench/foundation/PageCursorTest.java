package com.rivell.workbench.foundation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class PageCursorTest {

    @Test
    void roundTripsMicrosecondPrecisionExactly() {
        var createdAt = Instant.parse("2026-02-03T04:05:06.123456Z");
        var id = UUID.fromString("11111111-2222-3333-4444-555555555555");

        var decoded = PageCursor.decode(PageCursor.encode(new PageCursor(createdAt, id)));

        assertThat(decoded.createdAt()).isEqualTo(createdAt);
        assertThat(decoded.id()).isEqualTo(id);
    }

    @Test
    void twoPositionsInTheSameMillisecondStayDistinct() {
        var low = Instant.parse("2026-02-03T04:05:06.123001Z");
        var high = Instant.parse("2026-02-03T04:05:06.123999Z");

        var decodedLow =
                PageCursor.decode(PageCursor.encode(new PageCursor(low, UUID.randomUUID())));
        var decodedHigh =
                PageCursor.decode(PageCursor.encode(new PageCursor(high, UUID.randomUUID())));

        assertThat(decodedLow.createdAt()).isEqualTo(low);
        assertThat(decodedHigh.createdAt()).isEqualTo(high);
        assertThat(decodedLow.createdAt()).isNotEqualTo(decodedHigh.createdAt());
    }

    @Test
    void wholeSecondAndMillisecondValuesAlsoRoundTrip() {
        for (var text : new String[] {
                "2026-02-03T04:05:06Z",
                "2026-02-03T04:05:06.123Z",
                "2026-02-03T04:05:06.000001Z" }) {
            var createdAt = Instant.parse(text);
            var decoded =
                    PageCursor.decode(PageCursor.encode(new PageCursor(createdAt, UUID.randomUUID())));
            assertThat(decoded.createdAt()).as(text).isEqualTo(createdAt);
        }
    }

    @Test
    void anAbsentTokenIsTheFirstPage() {
        assertThat(PageCursor.decode(null)).isNull();
    }

    @Test
    void malformedTokensAreRejectedRatherThanGuessed() {
        assertThatThrownBy(() -> PageCursor.decode("not-base64!!"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> PageCursor.decode(Base64.getUrlEncoder().withoutPadding()
                .encodeToString("garbage".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> PageCursor.decode(Base64.getUrlEncoder().withoutPadding()
                .encodeToString("2026-02-03T04:05:06Z|not-a-uuid".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> PageCursor.decode(Base64.getUrlEncoder().withoutPadding()
                .encodeToString("not-an-instant|11111111-2222-3333-4444-555555555555"
                        .getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> PageCursor.decode(Base64.getUrlEncoder().withoutPadding()
                .encodeToString("|".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}