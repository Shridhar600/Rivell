package com.rivell.workbench.review;

import java.util.UUID;

public class UnauthorizedReviewAccessException extends RuntimeException {

    public UnauthorizedReviewAccessException(UUID userId) {
        super("User " + userId + " is not authorized for this review operation");
    }
}
