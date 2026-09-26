package com.rivell.workbench.workflow;

import java.util.UUID;

public class ReviewAlreadyFinalizedException extends RuntimeException {

    public ReviewAlreadyFinalizedException(UUID reviewId) {
        super("Review " + reviewId + " is already finalized; decisions are immutable");
    }
}
