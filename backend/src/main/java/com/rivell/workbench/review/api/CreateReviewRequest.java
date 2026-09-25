package com.rivell.workbench.review.api;

/** Optional explicit review configuration; the profile pack is used when absent. */
public record CreateReviewRequest(String contractPackKey) {
}
