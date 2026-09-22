package com.rivell.workbench.foundation;

import java.time.Instant;
import java.util.Objects;

/**
 * Uniform success envelope for every JSON API response: the payload {@code data}, the
 * HTTP {@code status}, and the server {@code timestamp}. Error responses use
 * {@code ErrorResponse} directly. Binary payloads (document downloads) bypass the
 * envelope because they are not JSON. Named {@code ApiResult} to stay unambiguous
 * next to the swagger {@code @ApiResponse} annotation.
 */
public record ApiResult<T>(T data, int status, Instant timestamp) {

    public ApiResult {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(timestamp, "timestamp");
    }

    public static <T> ApiResult<T> ok(T data) {
        return new ApiResult<>(data, 200, Instant.now());
    }
}
