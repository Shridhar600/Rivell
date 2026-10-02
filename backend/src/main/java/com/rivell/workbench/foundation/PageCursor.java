package com.rivell.workbench.foundation;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Opaque keyset pagination position for the organization-wide list reads.
 *
 * <p>Carries the last row's {@code (createdAt, id)}. That pair is the strict total order
 * the list is ordered by, so a page is resumed by comparison rather than by offset and
 * no row can be skipped or repeated when rows share a timestamp.
 *
 * <p>{@code createdAt} is carried at full precision and is never truncated to
 * milliseconds. The column is {@code TIMESTAMPTZ}, which holds microseconds; a
 * millisecond-truncated cursor is a value that does not exist in the table, and the
 * resume comparison would then silently skip rows sharing that millisecond.
 *
 * <p>Both components are already returned to the caller as {@code createdAt} and
 * {@code id} on every row, so the token discloses nothing new. It is opaque only in
 * that the total order cannot be hand-edited into an arbitrary position.
 */
public record PageCursor(Instant createdAt, UUID id) {

    private static final char SEPARATOR = '|';

    public static String encode(PageCursor position) {
        var raw = position.createdAt() + String.valueOf(SEPARATOR) + position.id();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decodes a resume position, or returns null for a first page.
     *
     * @throws IllegalArgumentException if the token is not a position this server minted
     */
    public static PageCursor decode(String token) {
        if (token == null) {
            return null;
        }
        final String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("cursor is not a valid page position");
        }
        var separator = raw.indexOf(SEPARATOR);
        if (separator < 1 || separator == raw.length() - 1) {
            throw new IllegalArgumentException("cursor is not a valid page position");
        }
        try {
            return new PageCursor(
                    Instant.parse(raw.substring(0, separator)),
                    UUID.fromString(raw.substring(separator + 1)));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("cursor is not a valid page position");
        }
    }
}