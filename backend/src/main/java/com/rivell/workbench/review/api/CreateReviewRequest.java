package com.rivell.workbench.review.api;

/**
 * Optional explicit review configuration; the profile pack is used when absent.
 *
 * <p>{@code clientRequestId} makes creation idempotent: repeating a request with the same key
 * returns the original review instead of freezing a second snapshot and paying for a second
 * analysis. It is optional, so a client that sends nothing keeps the existing behaviour.
 */
public record CreateReviewRequest(String contractPackKey, java.util.UUID clientRequestId) {
}
